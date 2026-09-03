package com.smartexam.stats;

import java.math.BigDecimal;
import java.util.List;

/**
 * 统计分析模块的响应模型。
 *
 * <p>这里的所有数字都是「当场从业务表算出来的」，不维护任何统计冗余表：本项目的数据量以课程演示为量级，
 * 实时聚合的代价远小于维护一份可能与业务数据不一致的汇总表。
 *
 * <p>两个刻意的口径约定，写在模型上以免实现和界面各说一套：
 * <ul>
 *   <li><b>题库口径按创建者裁剪。</b>题库列表本身就只返回教师自己创建的题目
 *       （见 {@code QuestionRepository#findPage} 的 {@code created_by} 条件），
 *       统计若统计全库，会出现「统计说 20 题、题库列表只有 10 题」的矛盾。</li>
 *   <li><b>未评分的主观题不进分数统计。</b>未评分时 {@code score} 是默认的 0，
 *       计入平均分会得到一个随阅卷进度变化的假数字，因此主观题只统计已评分的那部分，
 *       并单独给出 {@code ungradedCount} 说明还剩多少没批。</li>
 * </ul>
 */
public final class StatsModels {
    private StatsModels() {}

    /**
     * 通用分组计数行，用于题型、难度、知识点三种分布。
     *
     * @param key   机器可读的分组值（如 {@code SINGLE_CHOICE}），前端据此排序或上色
     * @param label 可直接展示的中文名
     * @param count 该分组的题目数量
     */
    public record GroupCount(String key, String label, long count) {}

    /**
     * 题库概况。
     *
     * @param total               题目总数（含已停用）
     * @param active              启用中的题目数；只有启用中的题目才能加入新试卷
     * @param knowledgePointCount 知识点数量
     */
    public record BankOverview(long total, long active, long disabled, long knowledgePointCount,
            List<GroupCount> byType, List<GroupCount> byDifficulty, List<GroupCount> byKnowledgePoint) {}

    /**
     * 教学活动概况，范围同样限定为当前教师创建的试卷与考试。
     *
     * @param publishedExamCount       已发布及之后状态的考试数（即学生可能见到过的考试）
     * @param pendingSubjectiveCount   全部考试合计仍未评分的主观题数量，是「还要批多少」的直接答案
     */
    public record ActivityOverview(long paperCount, long publishedPaperCount, long examCount,
            long publishedExamCount, long resultsPublishedExamCount, long submissionCount,
            long inProgressCount, long pendingSubjectiveCount) {}

    /** 总览：题库 + 教学活动。一次请求返回，避免首屏发两个请求。 */
    public record OverviewView(BankOverview bank, ActivityOverview activity) {}

    /**
     * 成绩分布的一个分数段。
     *
     * <p>分段按「占试卷满分的百分比」而不是绝对分数：试卷总分可以是 40、100 或任意值，
     * 用百分比分段才能让不同试卷的分布图有可比性。
     *
     * @param ratio 该段人数占已评完答卷数的百分比，保留一位小数
     */
    public record ScoreBucket(String label, int count, BigDecimal ratio) {}

    /**
     * 逐题作答统计，也就是课程要求里的「题目正确率」。
     *
     * @param subjective    主观题为 {@code true}；主观题没有「对/错」，因此 {@code correctRate} 为 {@code null}
     * @param answeredCount 实际写了内容的人数
     * @param blankCount    留空人数；留空必然得 0 分，是分析低分题的第一线索
     * @param fullMarkCount 满分人数；客观题即答对人数（本项目客观题不给部分分），主观题为 {@code null}
     * @param correctRate   正确率百分比，仅客观题有值
     * @param averageScore  平均得分，主观题只统计已评分的答卷
     * @param scoreRate     得分率 = 平均得分 / 本题满分，跨题型可比
     * @param ungradedCount 尚未评分的答卷数；大于 0 时上面的平均分只代表已批部分
     */
    public record QuestionStat(long paperQuestionId, int displayOrder, String type, String stem,
            BigDecimal maxScore, boolean subjective, int totalCount, int answeredCount, int blankCount,
            Integer fullMarkCount, BigDecimal correctRate, BigDecimal averageScore, BigDecimal scoreRate,
            int ungradedCount) {}

    /**
     * 单场考试的分析结果：整体统计 + 成绩分布 + 逐题正确率。
     *
     * <p>整体统计（平均分、最高分、最低分、及格率）直接复用 {@code GradingService#results}，
     * 不在本模块重算一遍——同一个数字有两处实现，早晚会对不上。
     *
     * @param submissionCount 全部答卷数（含仍在作答）
     * @param gradedCount     已评完并计入统计的答卷数
     * @param passScore       及格分数线（试卷满分 × 60%），把「及格率怎么来的」显式写进响应
     */
    public record ExamAnalysisView(long examId, String examName, String examStatus, boolean resultsPublished,
            BigDecimal paperTotalScore, BigDecimal passScore, int submissionCount, int gradedCount,
            BigDecimal averageScore, BigDecimal highestScore, BigDecimal lowestScore, BigDecimal passRate,
            List<ScoreBucket> distribution, List<QuestionStat> questions) {}
}
