package com.smartexam.exam;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 阅卷与成绩模块的请求响应模型。
 *
 * <p>这些视图的字段可见性随「谁在看」和「成绩是否已公布」变化，是本项目里最容易泄露信息的地方，
 * 因此把裁剪规则集中写在这里，实现见 {@code GradingService#detail}：
 * <ul>
 *   <li><b>教师</b>看自己创建的考试：全部字段可见，含标准答案、逐题得分和评语；</li>
 *   <li><b>学生</b>在成绩公布前：只能看到自己的作答，得分、标准答案、解析、评语、名次一律为 {@code null}；</li>
 *   <li><b>学生</b>在成绩公布后：可以看到本人答卷的全部内容和本人名次，但看不到其他学生的任何数据。</li>
 * </ul>
 * 分数类字段刻意用可空的 {@link BigDecimal} 而不是 0：0 分和「不给你看」是两件事，
 * 用 {@code null} 表示后者，前端才能区分「这题得 0 分」和「还没公布」。
 */
public final class GradingModels {
    private GradingModels() {}

    /**
     * 主观题题型：只能由教师人工评分，交卷时不参与客观题自动判分。
     *
     * <p>判分（{@code ExamService}）、阅卷统计（{@code GradingRepository}）和字段裁剪三处都读这一份定义，
     * 新增主观题题型时只改这里，不会出现「判分当客观题、阅卷当主观题」的分裂。
     */
    public static final java.util.Set<String> SUBJECTIVE_TYPES = java.util.Set.of("SHORT_ANSWER", "PROGRAMMING");

    /**
     * 主观题评分请求。
     *
     * <p>只校验「非负、最多一位小数」这两条格式规则；「不能超过该题满分」需要先查出题目分值，
     * 属于业务校验，放在 {@code GradingService#score} 里做。
     */
    public record ScoreRequest(@NotNull @DecimalMin("0.0") @Digits(integer = 5, fraction = 1) BigDecimal score,
            @Size(max = 1000) String comment) {}

    /**
     * 答卷中的一道题及其作答、得分与评语。
     *
     * @param id            答案记录 ID，主观题评分接口用它定位，不是题目 ID
     * @param maxScore      该题在本试卷中的满分，评分时的上界
     * @param answerContent 学生作答；未作答为 JSON null
     * @param subjective    是否为主观题；前端据此决定这一题显示评分框还是显示自动判分结果
     */
    public record AnswerDetailView(long id, long paperQuestionId, int displayOrder, String type, String stem,
            JsonNode options, BigDecimal maxScore, JsonNode answerContent, JsonNode standardAnswer,
            String explanation, BigDecimal score, String gradingComment, Instant gradedAt, boolean subjective) {}

    /**
     * 答卷详情。教师阅卷、学生答题恢复和学生成绩回看三个场景共用同一个结构，
     * 差别只在字段是否被裁剪为 {@code null}。
     *
     * @param resultsPublished 该场考试是否已公布成绩，前端据此决定是否展示分数区
     * @param rank             本人名次；未公布或答卷未评完时为 {@code null}
     */
    public record SubmissionDetailView(long id, long examId, String examName, long studentId, String studentName,
            String status, Instant startedAt, Instant submittedAt, BigDecimal paperTotalScore,
            BigDecimal objectiveScore, BigDecimal subjectiveScore, BigDecimal totalScore,
            boolean resultsPublished, Integer rank, List<AnswerDetailView> answers) {}

    /**
     * 待阅卷列表中的一行。
     *
     * @param subjectiveCount 该答卷的主观题总数
     * @param pendingCount    其中尚未评分的数量；为 0 表示这份答卷已批完
     */
    public record GradingItemView(long submissionId, long studentId, String studentName, String status,
            Instant submittedAt, BigDecimal objectiveScore, BigDecimal subjectiveScore, BigDecimal totalScore,
            int subjectiveCount, int pendingCount) {}

    /**
     * 阅卷面板。
     *
     * @param inProgressCount 仍在作答的人数；大于 0 时不允许公布成绩，否则会把还没写完的卷子算进排名
     * @param pendingCount    全场未评分的主观题总数；为 0 是公布成绩的前置条件
     */
    public record GradingBoardView(long examId, String examName, BigDecimal paperTotalScore, String examStatus,
            boolean resultsPublished, int submissionCount, int inProgressCount, int pendingCount,
            List<GradingItemView> items) {}

    /** 排名中的一行。名次采用竞赛排名，同分并列且占用名次，例如 {@code 1、2、2、4}。 */
    public record RankingItemView(int rank, long submissionId, long studentId, String studentName,
            BigDecimal totalScore, BigDecimal objectiveScore, BigDecimal subjectiveScore) {}

    /**
     * 教师视角的成绩统计与完整排名。
     *
     * @param gradedCount 已评完并计入统计的答卷数；未评完的答卷不进统计，避免平均分被半成品拉低
     * @param passRate    及格率，按「总分 ≥ 试卷总分的 60%」计算，保留一位小数
     */
    public record ExamResultsView(long examId, String examName, BigDecimal paperTotalScore, String examStatus,
            boolean resultsPublished, Instant resultsPublishedAt, int gradedCount, BigDecimal averageScore,
            BigDecimal highestScore, BigDecimal lowestScore, BigDecimal passRate, List<RankingItemView> rankings) {}

    /**
     * 学生视角的一条成绩。
     *
     * <p>只有 {@code rank} 和 {@code totalCount}，没有其他学生的姓名和分数——
     * 学生能知道「我第 2 名，共 4 人」，但不能反推出别人考了多少分。
     */
    public record MyResultItemView(long submissionId, long examId, String examName, Instant submittedAt,
            BigDecimal objectiveScore, BigDecimal subjectiveScore, BigDecimal totalScore,
            BigDecimal paperTotalScore, int rank, int totalCount) {}
}
