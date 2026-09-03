package com.smartexam.stats;

import com.smartexam.exam.GradingModels;
import com.smartexam.stats.StatsModels.GroupCount;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 统计分析的数据访问。
 *
 * <p>只做两类事：分组计数，以及把一场考试的逐题作答「摊平」成行。
 *
 * <p>逐题正确率刻意不在 SQL 里算完，而是取出明细行后在 {@code StatsService} 里聚合。原因有三：
 * ① 判断「是否留空」要看 JSON 内容，MySQL 与 H2 的 JSON 函数不通用，用 SQL 写就得写两套；
 * ② 客观题和主观题的统计口径不同（主观题只统计已评分的），写成一句 SQL 会塞满 CASE WHEN；
 * ③ 数据量是「题数 × 答卷数」，课程演示规模下不到几百行，可读性比这点开销值钱。
 * 这与竞赛排名同样在 Java 里算是一致的取舍。
 *
 * <p>所有计数都带 {@code created_by} 或 {@code e.created_by} 条件：教师只应看到自己的数据，
 * 而不是全库汇总。
 */
@Repository
public class StatsRepository {
    private final JdbcClient jdbc;

    public StatsRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** 题目状态计数：总数、启用、停用一次查回，避免三次往返。 */
    public long[] questionStatusCounts(long teacherId) {
        return jdbc.sql("""
                SELECT COUNT(*) total,
                  COUNT(CASE WHEN status='ACTIVE' THEN 1 END) active,
                  COUNT(CASE WHEN status='DISABLED' THEN 1 END) disabled
                FROM question WHERE created_by=:creator
                """).param("creator", teacherId)
                .query((rs, row) -> new long[] { rs.getLong("total"), rs.getLong("active"), rs.getLong("disabled") })
                .single();
    }

    /**
     * 按题型或难度分组计数。
     *
     * <p>列名不能作为参数绑定，因此用白名单方式拼接：{@code column} 只允许来自本类调用方的两个字面量，
     * 不接受任何外部输入，避免拼接带来的注入面。
     */
    public List<GroupCount> countByColumn(long teacherId, String column, Map<String, String> labels) {
        String allowed = switch (column) {
            case "type", "difficulty" -> column;
            default -> throw new IllegalArgumentException("unsupported group column: " + column);
        };
        return jdbc.sql("SELECT " + allowed + " AS group_key,COUNT(*) AS group_count FROM question"
                        + " WHERE created_by=:creator GROUP BY " + allowed)
                .param("creator", teacherId)
                .query((rs, row) -> {
                    String key = rs.getString("group_key");
                    return new GroupCount(key, labels.getOrDefault(key, key), rs.getLong("group_count"));
                }).list();
    }

    /**
     * 按知识点分组计数。
     *
     * <p>用 LEFT JOIN 从知识点出发而不是从题目出发：一个还没出过题的知识点也应该显示为 0，
     * 那恰恰是教师需要看到的「这个知识点还没题」。
     */
    public List<GroupCount> countByKnowledgePoint(long teacherId) {
        return jdbc.sql("""
                SELECT k.id,k.name,COUNT(q.id) AS group_count
                FROM knowledge_point k
                LEFT JOIN question q ON q.knowledge_point_id=k.id AND q.created_by=:creator
                GROUP BY k.id,k.name ORDER BY group_count DESC,k.name
                """).param("creator", teacherId)
                .query((rs, row) -> new GroupCount(String.valueOf(rs.getLong("id")), rs.getString("name"),
                        rs.getLong("group_count"))).list();
    }

    /** 知识点总数。知识点是全局共享的分类，不按创建者裁剪。 */
    public long knowledgePointCount() {
        return jdbc.sql("SELECT COUNT(*) FROM knowledge_point").query(Long.class).single();
    }

    /** 试卷计数：总数与已发布数。 */
    public long[] paperCounts(long teacherId) {
        return jdbc.sql("""
                SELECT COUNT(*) total,COUNT(CASE WHEN status='PUBLISHED' THEN 1 END) published
                FROM paper WHERE created_by=:creator
                """).param("creator", teacherId)
                .query((rs, row) -> new long[] { rs.getLong("total"), rs.getLong("published") }).single();
    }

    /**
     * 考试计数：总数、已发布及之后、已公布成绩。
     *
     * <p>「已发布」用 {@code status<>'DRAFT'} 而不是 {@code status='PUBLISHED'}：考试发布后会随时间和阅卷进度
     * 流转到进行中、已结束、已公布，这些都属于「学生已经能看到」的考试，只有草稿不是。
     */
    public long[] examCounts(long teacherId) {
        return jdbc.sql("""
                SELECT COUNT(*) total,
                  COUNT(CASE WHEN status<>'DRAFT' THEN 1 END) published,
                  COUNT(CASE WHEN status='RESULTS_PUBLISHED' THEN 1 END) results_published
                FROM exam WHERE created_by=:creator
                """).param("creator", teacherId)
                .query((rs, row) -> new long[] { rs.getLong("total"), rs.getLong("published"),
                        rs.getLong("results_published") }).single();
    }

    /** 答卷计数：总数与仍在作答数，范围是当前教师创建的考试。 */
    public long[] submissionCounts(long teacherId) {
        return jdbc.sql("""
                SELECT COUNT(*) total,COUNT(CASE WHEN s.status='IN_PROGRESS' THEN 1 END) in_progress
                FROM submission s JOIN exam e ON e.id=s.exam_id WHERE e.created_by=:creator
                """).param("creator", teacherId)
                .query((rs, row) -> new long[] { rs.getLong("total"), rs.getLong("in_progress") }).single();
    }

    /** 当前教师全部考试合计仍未评分的主观题数量；判断依据是 {@code graded_at} 为空，不是分数为 0。 */
    public long pendingSubjectiveCount(long teacherId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM submission_answer sa
                JOIN paper_question pq ON pq.id=sa.paper_question_id
                JOIN submission s ON s.id=sa.submission_id
                JOIN exam e ON e.id=s.exam_id
                WHERE e.created_by=:creator AND s.status<>'IN_PROGRESS'
                  AND pq.type_snapshot IN (:subjective) AND sa.graded_at IS NULL
                """).param("creator", teacherId)
                .param("subjective", GradingModels.SUBJECTIVE_TYPES).query(Long.class).single();
    }

    /** 一场考试的答卷总数，含仍在作答的答卷。 */
    public int submissionCount(long examId) {
        return jdbc.sql("SELECT COUNT(*) FROM submission WHERE exam_id=:exam")
                .param("exam", examId).query(Integer.class).single().intValue();
    }

    /**
     * 一场考试的逐题作答明细。
     *
     * <p>三层 LEFT JOIN 的顺序是有意的：从试卷题目出发，保证没有任何人作答的题目也出现在结果里
     * （{@code answerId=0} 表示这一行没有对应的作答记录）；只统计已交卷的答卷，
     * 仍在作答的答卷不能进正确率，否则会把「还没写」当成「写错了」。
     */
    public List<AnswerRow> findAnswerRows(long examId) {
        return jdbc.sql("""
                SELECT pq.id AS paper_question_id,pq.display_order,pq.type_snapshot,pq.stem_snapshot,pq.score AS max_score,
                  COALESCE(s.id,0) AS submission_id,COALESCE(sa.id,0) AS answer_id,sa.answer_content,sa.score AS got_score,
                  CASE WHEN sa.graded_at IS NULL THEN 0 ELSE 1 END AS graded
                FROM exam e
                JOIN paper_question pq ON pq.paper_id=e.paper_id
                LEFT JOIN submission s ON s.exam_id=e.id AND s.status<>'IN_PROGRESS'
                LEFT JOIN submission_answer sa ON sa.submission_id=s.id AND sa.paper_question_id=pq.id
                WHERE e.id=:exam ORDER BY pq.display_order,sa.id
                """).param("exam", examId)
                .query((rs, row) -> new AnswerRow(rs.getLong("paper_question_id"), rs.getInt("display_order"),
                        rs.getString("type_snapshot"), rs.getString("stem_snapshot"), rs.getBigDecimal("max_score"),
                        rs.getLong("submission_id"), rs.getLong("answer_id"), rs.getString("answer_content"),
                        rs.getBigDecimal("got_score"), rs.getInt("graded") == 1)).list();
    }

    /**
     * 一道题在一份答卷中的作答明细。
     *
     * @param submissionId  所属答卷 ID；为 0 表示这场考试还没有已交卷的答卷，该行只是为了让题目出现在统计里
     * @param answerId      作答记录 ID；为 0 表示这份答卷没有这道题的作答记录，等价于留空
     * @param answerContent 作答内容的原始 JSON 文本；{@code null} 与字符串 {@code "null"} 都表示未作答
     * @param graded        是否已人工评分；客观题由系统判分，这里为 {@code false}
     */
    public record AnswerRow(long paperQuestionId, int displayOrder, String type, String stem, BigDecimal maxScore,
            long submissionId, long answerId, String answerContent, BigDecimal score, boolean graded) {}
}
