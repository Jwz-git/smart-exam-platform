package com.smartexam.exam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.exam.GradingModels.AnswerDetailView;
import com.smartexam.exam.GradingModels.GradingItemView;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 阅卷与成绩的数据访问。
 *
 * <p>与 {@link ExamRepository} 的分工是「读不读答案」：{@code ExamRepository} 服务于答题过程，
 * 查询里刻意不含标准答案，避免下发给学生；本类服务于阅卷和成绩回看，必须读出标准答案、
 * 解析、逐题得分和评语，因此裁剪责任落在上层的 {@code GradingService}，
 * 由它按「谁在看、是否已公布」把字段置为 {@code null}。
 *
 * <p>主观题题型不写成 SQL 字面量，而是用 {@link GradingModels#SUBJECTIVE_TYPES} 作为命名参数展开，
 * 保证判分、阅卷、统计三处对「哪些题型要人工评分」的判断永远一致。
 */
@Repository
public class GradingRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public GradingRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc; this.json = json;
    }

    /** 考试头信息，用于阅卷面板与成绩页的标题区，同时提供归属校验所需的 {@code createdBy}。 */
    public Optional<ExamHeader> findExamHeader(long examId) {
        return jdbc.sql("""
                SELECT e.id,e.name,e.status,e.created_by,e.results_published_at,p.total_score
                FROM exam e JOIN paper p ON p.id=e.paper_id WHERE e.id=:exam
                """).param("exam", examId)
                .query((rs, row) -> new ExamHeader(rs.getLong("id"), rs.getString("name"), rs.getString("status"),
                        rs.getLong("created_by"), instant(rs, "results_published_at"), rs.getBigDecimal("total_score")))
                .optional();
    }

    /**
     * 一场考试的全部答卷及其阅卷进度。
     *
     * <p>两个标量子查询分别统计「主观题总数」和「其中未评分的数量」。判断是否已评分只看
     * {@code graded_at} 是否为空，不能看分数是否为 0——教师完全可以给一道主观题打 0 分，
     * 那也是已评分。
     */
    public List<GradingItemView> findGradingItems(long examId) {
        return jdbc.sql("""
                SELECT s.id,s.student_id,u.display_name,s.status,s.submitted_at,
                  s.objective_score,s.subjective_score,s.total_score,
                  (SELECT COUNT(*) FROM submission_answer sa JOIN paper_question pq ON pq.id=sa.paper_question_id
                     WHERE sa.submission_id=s.id AND pq.type_snapshot IN (:subjective)) subjective_count,
                  (SELECT COUNT(*) FROM submission_answer sa JOIN paper_question pq ON pq.id=sa.paper_question_id
                     WHERE sa.submission_id=s.id AND pq.type_snapshot IN (:subjective) AND sa.graded_at IS NULL) pending_count
                FROM submission s JOIN app_user u ON u.id=s.student_id
                WHERE s.exam_id=:exam ORDER BY s.id
                """).param("exam", examId).param("subjective", GradingModels.SUBJECTIVE_TYPES)
                .query((rs, row) -> new GradingItemView(rs.getLong("id"), rs.getLong("student_id"),
                        rs.getString("display_name"), rs.getString("status"), instant(rs, "submitted_at"),
                        rs.getBigDecimal("objective_score"), rs.getBigDecimal("subjective_score"),
                        rs.getBigDecimal("total_score"), rs.getInt("subjective_count"), rs.getInt("pending_count")))
                .list();
    }

    /** 答卷头信息。一次查出考试、试卷、学生三边的字段，省掉调用方再查三次。 */
    public Optional<SubmissionHeader> findSubmissionHeader(long submissionId) {
        return jdbc.sql("""
                SELECT s.id,s.exam_id,e.name exam_name,e.status exam_status,e.created_by exam_created_by,
                  e.results_published_at,s.student_id,u.display_name,s.status,s.started_at,s.submitted_at,
                  p.total_score paper_total,s.objective_score,s.subjective_score,s.total_score
                FROM submission s JOIN exam e ON e.id=s.exam_id JOIN paper p ON p.id=e.paper_id
                JOIN app_user u ON u.id=s.student_id WHERE s.id=:submission
                """).param("submission", submissionId)
                .query((rs, row) -> new SubmissionHeader(rs.getLong("id"), rs.getLong("exam_id"),
                        rs.getString("exam_name"), rs.getString("exam_status"), rs.getLong("exam_created_by"),
                        instant(rs, "results_published_at"), rs.getLong("student_id"), rs.getString("display_name"),
                        rs.getString("status"), instant(rs, "started_at"), instant(rs, "submitted_at"),
                        rs.getBigDecimal("paper_total"), rs.getBigDecimal("objective_score"),
                        rs.getBigDecimal("subjective_score"), rs.getBigDecimal("total_score")))
                .optional();
    }

    /**
     * 答卷的逐题明细，含标准答案、解析、得分和评语。
     *
     * <p>从 {@code paper_question} 出发再 LEFT JOIN 答案表，而不是反过来：
     * 学生完全没碰过的题目也必须出现在列表里，否则教师阅卷时会看不到漏答的题，
     * 学生回看时也会以为试卷少了一道题。这类行的 {@code id} 为 0，
     * 表示还没有答案记录、不能被评分。
     */
    public List<AnswerDetailView> findAnswers(long submissionId) {
        return jdbc.sql("""
                SELECT sa.id,pq.id paper_question_id,pq.display_order,pq.type_snapshot,pq.stem_snapshot,
                  pq.options_snapshot,pq.score max_score,sa.answer_content,pq.answer_snapshot,
                  pq.explanation_snapshot,sa.score,sa.grading_comment,sa.graded_at
                FROM submission s JOIN exam e ON e.id=s.exam_id JOIN paper_question pq ON pq.paper_id=e.paper_id
                LEFT JOIN submission_answer sa ON sa.submission_id=s.id AND sa.paper_question_id=pq.id
                WHERE s.id=:submission ORDER BY pq.display_order
                """).param("submission", submissionId).query((rs, row) -> {
                    String type = rs.getString("type_snapshot");
                    return new AnswerDetailView(rs.getLong("id"), rs.getLong("paper_question_id"),
                            rs.getInt("display_order"), type, rs.getString("stem_snapshot"),
                            readJson(rs.getString("options_snapshot")), rs.getBigDecimal("max_score"),
                            readJson(rs.getString("answer_content")), readJson(rs.getString("answer_snapshot")),
                            rs.getString("explanation_snapshot"), rs.getBigDecimal("score"),
                            rs.getString("grading_comment"), instant(rs, "graded_at"),
                            GradingModels.SUBJECTIVE_TYPES.contains(type));
                }).list();
    }

    /**
     * 参与排名统计的答卷，按总分降序。
     *
     * <p>入选条件是「已交卷」且「没有未评分的主观题」，不是简单地看
     * {@code status='GRADED'}：一份全是客观题的答卷交卷后就已经算完分，
     * 没有任何主观题需要教师批阅，也应当直接进入排名。
     *
     * <p>并列时按 {@code id} 兜底排序，保证同分学生的相对顺序在多次查询间稳定，
     * 否则教师两次刷新看到的名单顺序可能不同。名次本身在 Java 里按竞赛规则计算。
     */
    public List<RankingRow> findRankingRows(long examId) {
        return jdbc.sql("""
                SELECT s.id,s.student_id,u.display_name,s.total_score,s.objective_score,s.subjective_score
                FROM submission s JOIN app_user u ON u.id=s.student_id
                WHERE s.exam_id=:exam AND s.status<>'IN_PROGRESS' AND NOT EXISTS (
                  SELECT 1 FROM submission_answer sa JOIN paper_question pq ON pq.id=sa.paper_question_id
                  WHERE sa.submission_id=s.id AND pq.type_snapshot IN (:subjective) AND sa.graded_at IS NULL)
                ORDER BY s.total_score DESC,s.id
                """).param("exam", examId).param("subjective", GradingModels.SUBJECTIVE_TYPES)
                .query((rs, row) -> new RankingRow(rs.getLong("id"), rs.getLong("student_id"),
                        rs.getString("display_name"), rs.getBigDecimal("total_score"),
                        rs.getBigDecimal("objective_score"), rs.getBigDecimal("subjective_score")))
                .list();
    }

    /** 待评分答案的上下文，供 {@code GradingService} 校验归属、题型和分值上界。 */
    public Optional<AnswerContext> findAnswerContext(long answerId) {
        return jdbc.sql("""
                SELECT sa.id,s.id submission_id,s.status submission_status,e.id exam_id,e.created_by exam_created_by,
                  e.status exam_status,pq.type_snapshot,pq.score max_score
                FROM submission_answer sa JOIN submission s ON s.id=sa.submission_id
                JOIN exam e ON e.id=s.exam_id JOIN paper_question pq ON pq.id=sa.paper_question_id
                WHERE sa.id=:answer
                """).param("answer", answerId)
                .query((rs, row) -> new AnswerContext(rs.getLong("id"), rs.getLong("submission_id"),
                        rs.getString("submission_status"), rs.getLong("exam_id"), rs.getLong("exam_created_by"),
                        rs.getString("exam_status"), rs.getString("type_snapshot"), rs.getBigDecimal("max_score")))
                .optional();
    }

    /** 写入主观题得分与评语，同时记录评分人和评分时间。{@code graded_at} 是「已评分」的唯一判据。 */
    public void scoreSubjective(long answerId, BigDecimal score, String comment, long graderId, Instant at) {
        jdbc.sql("""
                UPDATE submission_answer SET score=:score,grading_comment=:comment,graded_by=:grader,graded_at=:at
                WHERE id=:answer
                """).param("score", score).param("comment", comment).param("grader", graderId)
                .param("at", at).param("answer", answerId).update();
    }

    /** 该答卷主观题的实得分合计。没有主观题时返回 0，不返回 null，便于直接参与加法。 */
    public BigDecimal subjectiveTotal(long submissionId) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(sa.score),0) FROM submission_answer sa
                JOIN paper_question pq ON pq.id=sa.paper_question_id
                WHERE sa.submission_id=:submission AND pq.type_snapshot IN (:subjective)
                """).param("submission", submissionId).param("subjective", GradingModels.SUBJECTIVE_TYPES)
                .query(BigDecimal.class).single();
    }

    /** 该答卷仍未评分的主观题数量。为 0 表示这份卷子已批完。 */
    public int pendingCount(long submissionId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM submission_answer sa JOIN paper_question pq ON pq.id=sa.paper_question_id
                WHERE sa.submission_id=:submission AND pq.type_snapshot IN (:subjective) AND sa.graded_at IS NULL
                """).param("submission", submissionId).param("subjective", GradingModels.SUBJECTIVE_TYPES)
                .query(Integer.class).single();
    }

    /** 全场未评分的主观题总数。为 0 是公布成绩的前置条件之一。 */
    public int examPendingCount(long examId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM submission_answer sa JOIN submission s ON s.id=sa.submission_id
                JOIN paper_question pq ON pq.id=sa.paper_question_id
                WHERE s.exam_id=:exam AND s.status<>'IN_PROGRESS'
                  AND pq.type_snapshot IN (:subjective) AND sa.graded_at IS NULL
                """).param("exam", examId).param("subjective", GradingModels.SUBJECTIVE_TYPES)
                .query(Integer.class).single();
    }

    /** 仍在作答的人数。大于 0 时不能公布成绩，否则半成品答卷会被算进排名和平均分。 */
    public int inProgressCount(long examId) {
        return jdbc.sql("SELECT COUNT(*) FROM submission WHERE exam_id=:exam AND status='IN_PROGRESS'")
                .param("exam", examId).query(Integer.class).single();
    }

    /**
     * 回写主观题得分、总分与答卷状态。
     *
     * <p>{@code version+1} 是乐观锁计数，用于事后排查同一份答卷被改过几次；
     * 当前评分接口靠事务和「教师归属」串行化，没有并发改同一份卷子的场景。
     */
    public void applyGrades(long submissionId, BigDecimal subjective, BigDecimal total, String status) {
        jdbc.sql("""
                UPDATE submission SET subjective_score=:subjective,total_score=:total,status=:status,version=version+1
                WHERE id=:submission
                """).param("subjective", subjective).param("total", total).param("status", status)
                .param("submission", submissionId).update();
    }

    /**
     * 把已批完的答卷统一置为 {@code GRADED}。
     *
     * <p>全客观题的答卷交卷后就已经算完分，没有主观题需要教师批阅，状态却停在 {@code SUBMITTED}。
     * 公布成绩时统一推进一次，让 {@code submission.status} 与「是否计入排名」表达同一件事，
     * 便于事后按状态直接查已出分的答卷。
     */
    public void markFullyGraded(long examId) {
        jdbc.sql("""
                UPDATE submission SET status='GRADED' WHERE exam_id=:exam AND status='SUBMITTED' AND NOT EXISTS (
                  SELECT 1 FROM submission_answer sa JOIN paper_question pq ON pq.id=sa.paper_question_id
                  WHERE sa.submission_id=submission.id AND pq.type_snapshot IN (:subjective) AND sa.graded_at IS NULL)
                """).param("exam", examId).param("subjective", GradingModels.SUBJECTIVE_TYPES).update();
    }

    /** 把考试置为已公布成绩并记录公布时间。状态前置条件由 Service 校验。 */
    public void publishResults(long examId, Instant at) {
        jdbc.sql("UPDATE exam SET status='RESULTS_PUBLISHED',results_published_at=:at WHERE id=:exam")
                .param("at", at).param("exam", examId).update();
    }

    /** 某学生已公布成绩的全部答卷，最近的考试排在前面。 */
    public List<MyResultRow> findMyResults(long studentId) {
        return jdbc.sql("""
                SELECT s.id,e.id exam_id,e.name exam_name,s.submitted_at,s.objective_score,s.subjective_score,
                  s.total_score,p.total_score paper_total
                FROM submission s JOIN exam e ON e.id=s.exam_id JOIN paper p ON p.id=e.paper_id
                WHERE s.student_id=:student AND e.status='RESULTS_PUBLISHED' AND s.status<>'IN_PROGRESS'
                ORDER BY e.id DESC
                """).param("student", studentId)
                .query((rs, row) -> new MyResultRow(rs.getLong("id"), rs.getLong("exam_id"), rs.getString("exam_name"),
                        instant(rs, "submitted_at"), rs.getBigDecimal("objective_score"),
                        rs.getBigDecimal("subjective_score"), rs.getBigDecimal("total_score"),
                        rs.getBigDecimal("paper_total")))
                .list();
    }

    /** 读取可空的时间列。 */
    private Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column); return timestamp == null ? null : timestamp.toInstant();
    }

    /** 解析数据库里的 JSON 文本；列为 SQL NULL 时返回 null，上层按「未作答」处理。 */
    private JsonNode readJson(String value) {
        if (value == null) return null;
        try { return json.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("数据库中的答案不是合法 JSON", exception); }
    }

    /** 考试头信息，含归属校验所需的创建者。 */
    public record ExamHeader(long id, String name, String status, long createdBy,
            Instant resultsPublishedAt, BigDecimal paperTotalScore) {}

    /** 答卷头信息，聚合了考试、试卷和学生三边的字段。 */
    public record SubmissionHeader(long id, long examId, String examName, String examStatus, long examCreatedBy,
            Instant resultsPublishedAt, long studentId, String studentName, String status, Instant startedAt,
            Instant submittedAt, BigDecimal paperTotalScore, BigDecimal objectiveScore,
            BigDecimal subjectiveScore, BigDecimal totalScore) {}

    /** 待评分答案的上下文。 */
    public record AnswerContext(long id, long submissionId, String submissionStatus, long examId,
            long examCreatedBy, String examStatus, String type, BigDecimal maxScore) {}

    /** 排名原始行，名次尚未计算。 */
    public record RankingRow(long submissionId, long studentId, String studentName, BigDecimal totalScore,
            BigDecimal objectiveScore, BigDecimal subjectiveScore) {}

    /** 学生本人成绩的原始行，名次由 Service 补齐。 */
    public record MyResultRow(long submissionId, long examId, String examName, Instant submittedAt,
            BigDecimal objectiveScore, BigDecimal subjectiveScore, BigDecimal totalScore,
            BigDecimal paperTotalScore) {}
}
