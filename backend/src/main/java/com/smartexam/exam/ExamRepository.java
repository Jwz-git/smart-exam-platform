package com.smartexam.exam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.exam.ExamModels.*;
import com.smartexam.question.QuestionModels.QuestionView;
import com.smartexam.question.QuestionRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 试卷、考试、答卷三张主表及其从表的读写。
 *
 * <p>时间字段统一按 UTC 存取。数据库连接必须带
 * {@code connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true}，
 * 否则由数据库生成的时间（如 {@code CURRENT_TIMESTAMP} 默认值）会按本地时区写入、
 * 按 UTC 读出，产生固定偏移——这个问题曾让答题倒计时多出 8 小时。
 */
@Repository
public class ExamRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final QuestionRepository questions;

    public ExamRepository(JdbcClient jdbc, ObjectMapper json, QuestionRepository questions) {
        this.jdbc = jdbc; this.json = json; this.questions = questions;
    }

    /**
     * 写入试卷及其题目快照，返回试卷主键。
     *
     * <p>快照是整个成绩可追溯性的基础：题干、选项、标准答案和解析都在这一刻复制进
     * {@code paper_question}。之后教师怎么改题库，已发布的试卷、已交的答卷和已算出的分数都不会变。
     */
    public long createPaper(PaperRequest request, long teacherId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO paper(name,duration_minutes,total_score,status,created_by) VALUES (:name,:duration,:total,'DRAFT',:teacher)")
                .param("name", request.name().trim()).param("duration", request.durationMinutes())
                .param("total", request.totalScore()).param("teacher", teacherId).update(keys, "id");
        long paperId = keys.getKey().longValue();
        for (int i = 0; i < request.questions().size(); i++) {
            PaperQuestionRequest item = request.questions().get(i);
            QuestionView question = questions.findById(item.questionId()).orElseThrow();
            jdbc.sql("""
                    INSERT INTO paper_question(paper_id,question_id,display_order,score,type_snapshot,stem_snapshot,
                      options_snapshot,answer_snapshot,explanation_snapshot)
                    VALUES (:paper,:question,:position,:score,:type,:stem,:options,:answer,:explanation)
                    """).param("paper", paperId).param("question", question.id()).param("position", i + 1)
                    .param("score", item.score()).param("type", question.type().name()).param("stem", question.stem())
                    .param("options", json.valueToTree(question.options()).toString())
                    .param("answer", question.standardAnswer().toString()).param("explanation", question.explanation()).update();
        }
        return paperId;
    }

    /** 按 ID 查试卷，附带题目列表。 */
    public Optional<PaperView> findPaper(long id) {
        return jdbc.sql("SELECT id,name,duration_minutes,total_score,status,created_by FROM paper WHERE id=:id")
                .param("id", id).query((rs, row) -> new PaperView(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("duration_minutes"), rs.getBigDecimal("total_score"), rs.getString("status"),
                        rs.getLong("created_by"), findPaperQuestions(rs.getLong("id")))).optional();
    }

    /** 查某位教师的全部试卷，最新的排在前面。 */
    public List<PaperView> findPapers(long teacherId) {
        return jdbc.sql("SELECT id,name,duration_minutes,total_score,status,created_by FROM paper WHERE created_by=:teacher ORDER BY id DESC")
                .param("teacher", teacherId).query((rs, row) -> new PaperView(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("duration_minutes"), rs.getBigDecimal("total_score"), rs.getString("status"),
                        rs.getLong("created_by"), findPaperQuestions(rs.getLong("id")))).list();
    }

    /**
     * 查试卷题目。
     *
     * <p>SELECT 里刻意不含 {@code answer_snapshot} 和 {@code explanation_snapshot}：
     * 这个方法的结果会下发给正在答题的学生，查出来就有泄题风险。
     */
    public List<PaperQuestionView> findPaperQuestions(long paperId) {
        return jdbc.sql("""
                SELECT id,question_id,display_order,score,type_snapshot,stem_snapshot,options_snapshot
                FROM paper_question WHERE paper_id=:paper ORDER BY display_order
                """).param("paper", paperId).query((rs, row) -> new PaperQuestionView(rs.getLong("id"),
                        rs.getLong("question_id"), rs.getInt("display_order"), rs.getBigDecimal("score"),
                        rs.getString("type_snapshot"), rs.getString("stem_snapshot"), readJson(rs.getString("options_snapshot")))).list();
    }

    /**
     * 取试卷导出所需的逐题数据。
     *
     * <p>与 {@link #findPaperQuestions} 的关键差别是<b>带标准答案和解析</b>：导出是教师本人的操作，
     * 由 {@code PaperController} 的角色规则与 {@code ExamService#paper} 的归属校验双重把关，
     * 不会下发给学生。两个方法因此刻意分开写，避免哪天有人给答题接口误用了带答案的那一个。
     *
     * <p>难度、知识点、标签三列不在快照里，只能回查 {@code question} 表，因此它们反映的是
     * <b>题库当前</b>的值，而题干、选项、答案、分值仍来自试卷快照。用 LEFT JOIN 兜底：
     * 被引用的题目按业务规则不会被物理删除（只会停用），但导出不该因为一条脏数据整个失败。
     */
    public List<PaperExportRow> findPaperExportRows(long paperId) {
        return jdbc.sql("""
                SELECT pq.display_order,pq.score,pq.type_snapshot,pq.stem_snapshot,pq.options_snapshot,
                  pq.answer_snapshot,pq.explanation_snapshot,q.difficulty,q.tags,k.name knowledge_point_name
                FROM paper_question pq
                LEFT JOIN question q ON q.id=pq.question_id
                LEFT JOIN knowledge_point k ON k.id=q.knowledge_point_id
                WHERE pq.paper_id=:paper ORDER BY pq.display_order
                """).param("paper", paperId)
                .query((rs, row) -> new PaperExportRow(rs.getInt("display_order"), rs.getBigDecimal("score"),
                        rs.getString("type_snapshot"), rs.getString("stem_snapshot"),
                        readJson(rs.getString("options_snapshot")), readJson(rs.getString("answer_snapshot")),
                        rs.getString("explanation_snapshot"), rs.getString("difficulty"),
                        rs.getString("knowledge_point_name"), rs.getString("tags")))
                .list();
    }

    /** 把试卷置为已发布。状态前置条件由 Service 校验。 */
    public void publishPaper(long id) {
        jdbc.sql("UPDATE paper SET status='PUBLISHED' WHERE id=:id").param("id", id).update();
    }

    /** 写入考试草稿，返回主键。 */
    public long createExam(ExamRequest request, long teacherId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO exam(name,paper_id,created_by,start_at,end_at,status) VALUES (:name,:paper,:teacher,:start,:end,'DRAFT')")
                .param("name", request.name().trim()).param("paper", request.paperId()).param("teacher", teacherId)
                .param("start", request.startAt()).param("end", request.endAt()).update(keys, "id");
        return keys.getKey().longValue();
    }

    /** 按 ID 查考试。{@code studentId} 非空时附带该学生的答卷状态，为空时传 -1 表示不匹配任何学生。 */
    public Optional<ExamView> findExam(long id, Long studentId) {
        String sql = examSelect() + " WHERE e.id=:id";
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("id", id).param("student", studentId == null ? -1L : studentId);
        return statement.query(this::mapExam).optional();
    }

    /** 教师的考试列表，含草稿。 */
    public List<ExamView> findTeacherExams(long teacherId) {
        return jdbc.sql(examSelect() + " WHERE e.created_by=:teacher ORDER BY e.id DESC")
                .param("teacher", teacherId).param("student", -1L).query(this::mapExam).list();
    }

    /** 学生可见的考试列表：只取已发布的，按开始时间排序，附带本人答卷状态。 */
    public List<ExamView> findStudentExams(long studentId) {
        return jdbc.sql(examSelect() + " WHERE e.status='PUBLISHED' ORDER BY e.start_at,e.id")
                .param("student", studentId).query(this::mapExam).list();
    }

    /** 把考试置为已发布。 */
    public void publishExam(long id) { jdbc.sql("UPDATE exam SET status='PUBLISHED' WHERE id=:id").param("id", id).update(); }

    /** 按 ID 查答卷，附带该场考试的题目列表和已保存的作答。 */
    public Optional<SubmissionView> findSubmission(long id) {
        return jdbc.sql("SELECT id,exam_id,status,started_at,submitted_at,objective_score FROM submission WHERE id=:id")
                .param("id", id).query((rs, row) -> new SubmissionView(rs.getLong("id"), rs.getLong("exam_id"),
                        rs.getString("status"), instant(rs, "started_at"), instant(rs, "submitted_at"),
                        rs.getBigDecimal("objective_score"), findSubmissionQuestions(rs.getLong("exam_id")),
                        findSavedAnswers(rs.getLong("id")))).optional();
    }

    /**
     * 读出该答卷已保存的作答，供刷新恢复使用。
     *
     * <p>只查题目 ID 和作答内容，不查分数、标准答案和评语：这个结果会跟着答卷视图
     * 下发到正在答题的学生浏览器，多查一列就是多一处泄题风险。
     */
    public List<SavedAnswerView> findSavedAnswers(long submissionId) {
        return jdbc.sql("""
                SELECT sa.paper_question_id,sa.answer_content FROM submission_answer sa
                JOIN paper_question pq ON pq.id=sa.paper_question_id
                WHERE sa.submission_id=:submission ORDER BY pq.display_order
                """).param("submission", submissionId)
                .query((rs, row) -> new SavedAnswerView(rs.getLong("paper_question_id"),
                        readJson(rs.getString("answer_content")))).list();
    }

    /** 查某学生在某场考试的答卷 ID。{@code (exam_id, student_id)} 上有唯一约束，最多一条。 */
    public Optional<Long> findSubmissionId(long examId, long studentId) {
        return jdbc.sql("SELECT id FROM submission WHERE exam_id=:exam AND student_id=:student")
                .param("exam", examId).param("student", studentId).query(Long.class).optional();
    }

    public long createSubmission(long examId, long studentId, Instant startedAt) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        // started_at 由后端显式写入：倒计时依赖它，不能受数据库会话时区影响。
        jdbc.sql("INSERT INTO submission(exam_id,student_id,status,started_at) VALUES (:exam,:student,'IN_PROGRESS',:started)")
                .param("exam", examId).param("student", studentId).param("started", startedAt).update(keys, "id");
        return keys.getKey().longValue();
    }

    /** 取答卷所属学生 ID；答卷不存在时返回 -1，使归属校验必然失败。 */
    public long submissionOwner(long id) {
        return jdbc.sql("SELECT student_id FROM submission WHERE id=:id").param("id", id).query(Long.class).optional().orElse(-1L);
    }

    /**
     * 对答卷加行级排他锁（{@code SELECT ... FOR UPDATE}）。
     *
     * <p>交卷路径必须先调用它再读状态，用来串行化并发的重复提交。
     * 锁在当前事务提交或回滚时自动释放。
     */
    public void lockSubmission(long id) {
        jdbc.sql("SELECT id FROM submission WHERE id=:id FOR UPDATE").param("id", id).query(Long.class).optional();
    }

    /**
     * 保存单题答案，先尝试更新、更新不到再插入。
     *
     * <p>{@code answerContent} 为 null 时写入 JSON 的 {@code null} 而不是 SQL NULL，
     * 这样「作答过又清空」和「从未作答」在读取时表现一致，判分时都视为未答。
     */
    public void saveAnswer(long submissionId, AnswerRequest answer) {
        int updated = jdbc.sql("UPDATE submission_answer SET answer_content=:answer WHERE submission_id=:submission AND paper_question_id=:question")
                .param("answer", answer.answerContent() == null ? "null" : answer.answerContent().toString())
                .param("submission", submissionId).param("question", answer.paperQuestionId()).update();
        if (updated == 0) jdbc.sql("INSERT INTO submission_answer(submission_id,paper_question_id,answer_content) VALUES (:submission,:question,:answer)")
                .param("submission", submissionId).param("question", answer.paperQuestionId())
                .param("answer", answer.answerContent() == null ? "null" : answer.answerContent().toString()).update();
    }

    /** 校验该试卷题目确实属于这份答卷所在的考试，防止把答案写到其他试卷的题目上。 */
    public boolean questionBelongsToSubmission(long submissionId, long paperQuestionId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM submission s JOIN exam e ON e.id=s.exam_id
                JOIN paper_question pq ON pq.paper_id=e.paper_id
                WHERE s.id=:submission AND pq.id=:question
                """).param("submission", submissionId).param("question", paperQuestionId).query(Long.class).single() > 0;
    }

    /**
     * 取判分所需的数据：题型、标准答案、分值和学生作答。
     *
     * <p>用 LEFT JOIN 而不是 INNER JOIN：完全没作答的题目也必须出现在结果里，
     * 否则漏题不会被判 0 分，而是根本不参与统计，总分就对不上了。
     */
    public List<AnswerRow> answersForScoring(long submissionId) {
        return jdbc.sql("""
                SELECT pq.id,pq.type_snapshot,pq.answer_snapshot,pq.score,sa.answer_content
                FROM submission s JOIN exam e ON e.id=s.exam_id JOIN paper_question pq ON pq.paper_id=e.paper_id
                LEFT JOIN submission_answer sa ON sa.submission_id=s.id AND sa.paper_question_id=pq.id
                WHERE s.id=:submission ORDER BY pq.display_order
                """).param("submission", submissionId).query((rs, row) -> new AnswerRow(rs.getLong("id"),
                        rs.getString("type_snapshot"), readJson(rs.getString("answer_snapshot")),
                        rs.getBigDecimal("score"), readJson(rs.getString("answer_content")))).list();
    }

    /** 写入单题得分；学生完全没作答时补插一条记录，保证每题都有得分行。 */
    public void scoreAnswer(long submissionId, long paperQuestionId, BigDecimal score) {
        int updated = jdbc.sql("UPDATE submission_answer SET score=:score WHERE submission_id=:submission AND paper_question_id=:question")
                .param("score", score).param("submission", submissionId).param("question", paperQuestionId).update();
        if (updated == 0) jdbc.sql("INSERT INTO submission_answer(submission_id,paper_question_id,answer_content,score) VALUES (:submission,:question,'null',:score)")
                .param("submission", submissionId).param("question", paperQuestionId).param("score", score).update();
    }

    /**
     * 把答卷置为已交卷并写入客观题得分。
     *
     * <p>此刻 {@code total_score} 先等于客观题得分，等教师批阅主观题后再重算。
     * 因此在成绩公布前不能把 {@code total_score} 当作最终总分展示给学生。
     */
    public void submit(long id, BigDecimal objectiveScore, Instant submittedAt) {
        jdbc.sql("UPDATE submission SET status='SUBMITTED',submitted_at=:at,objective_score=:score,total_score=:score,version=version+1 WHERE id=:id")
                .param("at", submittedAt).param("score", objectiveScore).param("id", id).update();
    }

    /**
     * 找出已过考试截止时间但仍在作答的答卷 ID。
     *
     * <p>目前只按考试的 {@code end_at} 判断，没有把「开始作答 + 试卷时长」算进去，
     * 也就是说试卷时长目前只由前端倒计时约束。补齐服务端约束属于待办项。
     */
    public List<Long> findExpiredInProgressSubmissions(Instant now) {
        return jdbc.sql("""
                SELECT s.id FROM submission s JOIN exam e ON e.id=s.exam_id
                WHERE s.status='IN_PROGRESS' AND e.end_at<=:now ORDER BY s.id
                """).param("now", now).query(Long.class).list();
    }

    /** 由考试反查试卷题目，供答卷视图使用。 */
    private List<PaperQuestionView> findSubmissionQuestions(long examId) {
        return jdbc.sql("SELECT paper_id FROM exam WHERE id=:id").param("id", examId).query(Long.class).optional()
                .map(this::findPaperQuestions).orElse(List.of());
    }

    /**
     * 考试查询的公共 SELECT 片段。
     *
     * <p>用 LEFT JOIN 关联当前学生的答卷，这样一次查询就能拿到「考试信息 + 我的答卷状态」，
     * 不必对每场考试再查一次。
     */
    private String examSelect() {
        return """
                SELECT e.id,e.name,e.paper_id,p.name paper_name,p.duration_minutes,p.total_score,e.start_at,e.end_at,
                  e.status,e.created_by,s.id submission_id,s.status submission_status
                FROM exam e JOIN paper p ON p.id=e.paper_id
                LEFT JOIN submission s ON s.exam_id=e.id AND s.student_id=:student
                """;
    }

    private ExamView mapExam(ResultSet rs, int row) throws SQLException {
        long submissionId = rs.getLong("submission_id");
        // wasNull() 只反映最近一次读取的列，必须紧跟 submission_id 判断，否则会被后续列覆盖。
        Long submission = rs.wasNull() ? null : submissionId;
        return new ExamView(rs.getLong("id"), rs.getString("name"), rs.getLong("paper_id"), rs.getString("paper_name"),
                rs.getInt("duration_minutes"), rs.getBigDecimal("total_score"), instant(rs, "start_at"), instant(rs, "end_at"),
                rs.getString("status"), rs.getLong("created_by"), submission, rs.getString("submission_status"));
    }

    /** 读取可空的时间列。 */
    private Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column); return timestamp == null ? null : timestamp.toInstant();
    }

    /** 解析数据库里的 JSON 文本；列为 SQL NULL 时返回 null，由判分逻辑当作未作答处理。 */
    private JsonNode readJson(String value) {
        if (value == null) return null;
        try { return json.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("数据库中的答案不是合法 JSON", exception); }
    }

    /**
     * 判分用的一行数据。
     *
     * @param type     题型快照，据此决定是否自动判分
     * @param expected 标准答案快照
     * @param score    该题满分
     * @param actual   学生作答，未作答时为 null
     */
    public record AnswerRow(long paperQuestionId, String type, JsonNode expected, BigDecimal score, JsonNode actual) {}

    /**
     * 试卷导出用的一行。
     *
     * @param type            题型快照
     * @param stem            题干快照
     * @param options         选项快照，非选择题为 JSON 空数组或 null
     * @param answer          标准答案快照
     * @param explanation     解析快照
     * @param difficulty      题库当前难度（快照里没有这一列），题目已不可查时为 null
     * @param knowledgePoint  题库当前知识点名称，同上
     * @param tags            题库当前标签，同上
     */
    public record PaperExportRow(int displayOrder, BigDecimal score, String type, String stem, JsonNode options,
            JsonNode answer, String explanation, String difficulty, String knowledgePoint, String tags) {}
}
