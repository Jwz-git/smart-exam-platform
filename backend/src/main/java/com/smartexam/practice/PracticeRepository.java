package com.smartexam.practice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.exam.GradingModels;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 错题本与练习记录的数据访问。
 *
 * <p>「什么算错题」在本类里只有一处定义，就是 {@link #WRONG_ANSWER_SOURCE} 这段 FROM/WHERE：
 * <ul>
 *   <li><b>只来自已公布成绩的考试</b>（{@code e.status='RESULTS_PUBLISHED'}）。这是硬约束：
 *       成绩公布前告诉学生「你这道错了」，等于提前泄露了成绩，也绕过了
 *       {@code GradingService} 精心做的字段裁剪；</li>
 *   <li><b>得分低于满分即算错</b>（{@code sa.score < pq.score}）。未作答（0 分）、答错（0 分）
 *       和主观题部分得分都会进错题本——一道 10 分的简答只得 4 分，同样值得回看；</li>
 *   <li>成绩已公布意味着全场主观题都已批完（这是公布的前置条件），因此不需要再判
 *       {@code graded_at IS NOT NULL}。</li>
 * </ul>
 * 三个查询共用这一段，避免「列表里是错题、练习集里不是」这种分裂。
 */
@Repository
public class PracticeRepository {
    /**
     * 错题来源的 FROM 部分：答卷 → 考试 → 逐题作答 → 试卷题目快照。
     *
     * <p>与 {@link #WRONG_WHERE} 拆成两段，中间才好插 {@link #PRACTICE_JOIN}：
     * SQL 里 JOIN 必须写在 WHERE 之前，拼成一整段就没法选择性地带上练习记录。
     */
    private static final String WRONG_FROM = """
            FROM submission s
            JOIN exam e ON e.id=s.exam_id
            JOIN submission_answer sa ON sa.submission_id=s.id
            JOIN paper_question pq ON pq.id=sa.paper_question_id
            """;

    /**
     * 错题的判定条件：本人、已公布成绩、已交卷、且该题未得满分。
     *
     * <p>参数只有 {@code :student}，三个查询都以它开头再补自己的额外条件。
     */
    private static final String WRONG_WHERE = """
            WHERE s.student_id=:student AND e.status='RESULTS_PUBLISHED' AND s.status<>'IN_PROGRESS'
              AND sa.score < pq.score
            """;

    /**
     * 练习记录的聚合：每道题练过几次、最近一次是哪条。
     *
     * <p>用「先聚合出 MAX(id)、再回连那一行」而不是窗口函数：两种数据库（MySQL 与测试用的 H2）
     * 都支持这种写法，也不必为一个小查询引入方言差异。
     */
    private static final String PRACTICE_JOIN = """
            LEFT JOIN (SELECT pa.paper_question_id,COUNT(*) attempt_count,MAX(pa.id) last_id
                       FROM practice_attempt pa WHERE pa.student_id=:student
                       GROUP BY pa.paper_question_id) agg ON agg.paper_question_id=pq.id
            LEFT JOIN practice_attempt newest ON newest.id=agg.last_id
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public PracticeRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc; this.json = json;
    }

    /**
     * 错题本全量列表，最近的考试排在前面。
     *
     * <p>带标准答案、解析和教师评语：这些内容在成绩公布后对本人是可见的，
     * 错题本的用途就是复习。练习集走 {@link #findPracticeCandidates}，那一个刻意不查这些列。
     */
    public List<WrongRow> findWrongQuestions(long studentId) {
        return jdbc.sql("""
                SELECT sa.id answer_id,s.id submission_id,pq.id paper_question_id,e.id exam_id,e.name exam_name,
                  pq.display_order,pq.type_snapshot,pq.stem_snapshot,pq.options_snapshot,pq.score max_score,
                  sa.score,sa.answer_content,pq.answer_snapshot,pq.explanation_snapshot,sa.grading_comment,
                  s.submitted_at,COALESCE(agg.attempt_count,0) attempt_count,newest.correct last_correct,
                  newest.attempted_at last_attempted_at
                """ + WRONG_FROM + PRACTICE_JOIN + WRONG_WHERE + " ORDER BY e.id DESC,pq.display_order")
                .param("student", studentId).query(this::mapWrongRow).list();
    }

    /**
     * 取一批可重练的错题。
     *
     * <p>三个刻意的限制：
     * <ol>
     *   <li><b>只取客观题</b>：主观题没有确定性判分规则，重练时无法给出对错；</li>
     *   <li><b>不查标准答案与解析</b>：这个结果会下发给正在练习的学生，查出来就等于给了答案；</li>
     *   <li>按「练得少的优先」排序，让没练过的题先出现，而不是每次都练同一批。</li>
     * </ol>
     *
     * @param onlyUnmastered 为 true 时排除「最近一次已练对」的题目
     */
    public List<PracticeRow> findPracticeCandidates(long studentId, boolean onlyUnmastered, int limit) {
        String filter = onlyUnmastered ? " AND (newest.correct IS NULL OR newest.correct=FALSE)" : "";
        return jdbc.sql("""
                SELECT pq.id paper_question_id,e.name exam_name,pq.type_snapshot,pq.stem_snapshot,
                  pq.options_snapshot,pq.score max_score,COALESCE(agg.attempt_count,0) attempt_count
                """ + WRONG_FROM + PRACTICE_JOIN + WRONG_WHERE
                + "  AND pq.type_snapshot NOT IN (:subjective)" + filter
                + " ORDER BY COALESCE(agg.attempt_count,0),pq.id LIMIT :limit")
                .param("student", studentId).param("subjective", GradingModels.SUBJECTIVE_TYPES)
                .param("limit", limit)
                .query((rs, row) -> new PracticeRow(rs.getLong("paper_question_id"), rs.getString("exam_name"),
                        rs.getString("type_snapshot"), rs.getString("stem_snapshot"),
                        readJson(rs.getString("options_snapshot")), rs.getBigDecimal("max_score"),
                        rs.getInt("attempt_count")))
                .list();
    }

    /**
     * 取一道题的判分上下文，同时完成「这道题是否真的在该学生的错题本里」这一校验。
     *
     * <p>两件事合在一个查询里是刻意的：练习提交的响应会返回标准答案和解析，
     * 因此必须先确认学生有权看到它。查不到就说明这道题不属于本人的已公布错题，
     * 由 Service 转成 404——既拦住越权，也避免用错误码差异透露「这个 ID 存在吗」。
     *
     * <p>同一份试卷被两场考试引用时，同一道题可能命中两行（学生在两场考试里都做过），
     * 此时取第一行即可：两行的题型、标准答案和满分都来自同一条快照，完全相同。
     */
    public Optional<GradingContext> findGradingContext(long studentId, long paperQuestionId) {
        return jdbc.sql("SELECT pq.type_snapshot,pq.answer_snapshot,pq.explanation_snapshot,pq.stem_snapshot\n"
                + WRONG_FROM + WRONG_WHERE + "  AND pq.id=:question")
                .param("student", studentId).param("question", paperQuestionId)
                .query((rs, row) -> new GradingContext(rs.getString("type_snapshot"),
                        readJson(rs.getString("answer_snapshot")), rs.getString("explanation_snapshot"),
                        rs.getString("stem_snapshot")))
                .list().stream().findFirst();
    }

    /**
     * 记录一次练习。
     *
     * <p>每次练习都插一行，不覆盖历史：这样既能算出「练了几次」，也能回答「练到第几次才对」。
     * 时间由后端显式写入，不用数据库默认值——与答卷开始时间同样的理由，避免会话时区造成偏移。
     */
    public void recordAttempt(long studentId, long paperQuestionId, JsonNode answer, boolean correct, Instant at) {
        jdbc.sql("""
                INSERT INTO practice_attempt(student_id,paper_question_id,answer_content,correct,attempted_at)
                VALUES (:student,:question,:answer,:correct,:at)
                """).param("student", studentId).param("question", paperQuestionId)
                .param("answer", answer == null ? "null" : answer.toString())
                .param("correct", correct).param("at", at).update();
    }

    /** 把一行错题映射成原始记录。 */
    private WrongRow mapWrongRow(ResultSet rs, int row) throws SQLException {
        // correct 列可空：从未练过时为 SQL NULL，而 getBoolean 对 NULL 会返回 false，
        // 那样「练过并且答错」和「从未练过」就分不开了，因此先用 getObject 判空。
        Boolean lastCorrect = rs.getObject("last_correct") == null ? null : rs.getBoolean("last_correct");
        return new WrongRow(rs.getLong("answer_id"), rs.getLong("submission_id"), rs.getLong("paper_question_id"),
                rs.getLong("exam_id"), rs.getString("exam_name"), rs.getInt("display_order"),
                rs.getString("type_snapshot"), rs.getString("stem_snapshot"),
                readJson(rs.getString("options_snapshot")), rs.getBigDecimal("max_score"),
                rs.getBigDecimal("score"), readJson(rs.getString("answer_content")),
                readJson(rs.getString("answer_snapshot")), rs.getString("explanation_snapshot"),
                rs.getString("grading_comment"), instant(rs, "submitted_at"), rs.getInt("attempt_count"),
                lastCorrect, instant(rs, "last_attempted_at"));
    }

    /** 读取可空的时间列。 */
    private Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** 解析数据库里的 JSON 文本；列为 SQL NULL 时返回 null。 */
    private JsonNode readJson(String value) {
        if (value == null) return null;
        try { return json.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("数据库中的答案不是合法 JSON", exception); }
    }

    /** 错题本的一行原始数据，名次以外的字段都直接来自数据库。 */
    public record WrongRow(long answerId, long submissionId, long paperQuestionId, long examId, String examName,
            int displayOrder, String type, String stem, JsonNode options, BigDecimal maxScore, BigDecimal score,
            JsonNode myAnswer, JsonNode standardAnswer, String explanation, String gradingComment,
            Instant submittedAt, int practiceCount, Boolean lastCorrect, Instant lastPracticedAt) {}

    /** 练习集的一行。刻意没有标准答案和解析两列。 */
    public record PracticeRow(long paperQuestionId, String examName, String type, String stem, JsonNode options,
            BigDecimal maxScore, int practiceCount) {}

    /** 判分上下文：题型、标准答案、解析与题干。 */
    public record GradingContext(String type, JsonNode standardAnswer, String explanation, String stem) {}
}
