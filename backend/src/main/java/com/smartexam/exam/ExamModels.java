package com.smartexam.exam;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 试卷、考试与答卷模块的请求响应模型。
 *
 * <p>命名上区分三个概念，不要混用：
 * <ul>
 *   <li><b>试卷（Paper）</b>：题目的有序集合，含每题分值和总分，可复用；</li>
 *   <li><b>考试（Exam）</b>：一次具体的考试安排，引用一份已发布试卷并规定开放时间；</li>
 *   <li><b>答卷（Submission）</b>：某个学生在某场考试中的作答记录，一人一场最多一份。</li>
 * </ul>
 */
public final class ExamModels {
    private ExamModels() {}

    /**
     * 组卷时的单题请求。
     *
     * <p>{@code score} 是这道题在本试卷中的实际分值，与题库里的建议分值相互独立——
     * 同一道题可以在不同试卷里给不同分数。{@code @Digits(fraction=1)} 与数据库
     * {@code DECIMAL(6,1)} 对齐，也对应「分值保留一位小数」的业务规则。
     */
    public record PaperQuestionRequest(@NotNull Long questionId,
            @NotNull @DecimalMin("0.1") @Digits(integer=5, fraction=1) BigDecimal score) {}

    /**
     * 创建试卷的请求。题目顺序即列表顺序，{@code totalScore} 必须严格等于各题分值之和，
     * 由 {@code ExamService#createPaper} 校验；前端的总分是自动汇总出来的，不允许手填不一致的值。
     */
    public record PaperRequest(@NotBlank @Size(max=150) String name,
            @Positive int durationMinutes,
            @NotNull @DecimalMin("0.1") @Digits(integer=5, fraction=1) BigDecimal totalScore,
            @NotEmpty @Valid List<PaperQuestionRequest> questions) {}

    /**
     * 试卷中的一道题（快照视图）。
     *
     * <p>刻意不包含标准答案和解析：这个结构会直接下发给正在答题的学生，
     * 一旦带上答案就等于把正确选项发到了浏览器里。教师阅卷需要答案时另走阅卷接口。
     *
     * @param id 试卷题目 ID，保存答案时用它定位，不是题库里的题目 ID
     */
    public record PaperQuestionView(long id, long questionId, int displayOrder, BigDecimal score,
            String type, String stem, JsonNode options) {}

    /** 试卷视图。{@code status} 为 {@code DRAFT} 或 {@code PUBLISHED}，只有已发布试卷能用于创建考试。 */
    public record PaperView(long id, String name, int durationMinutes, BigDecimal totalScore,
            String status, long createdBy, List<PaperQuestionView> questions) {}

    /**
     * 创建考试的请求。{@code @Future} 保证结束时间在未来（否则考试一创建就已经结束），
     * 「结束时间必须晚于开始时间」这条跨字段规则在 Service 里校验。
     * 时间一律是带时区的 ISO 8601 字符串，服务端按 UTC 存储。
     */
    public record ExamRequest(@NotBlank @Size(max=150) String name, @NotNull Long paperId,
            @NotNull Instant startAt, @NotNull @Future Instant endAt) {}

    /**
     * 考试视图。
     *
     * @param submissionId     当前学生在这场考试中的答卷 ID；尚未开始作答时为 {@code null}
     * @param submissionStatus 答卷状态，未开始作答时为 {@code null}，前端据此显示「进入考试 / 继续作答 / 已交卷」
     */
    public record ExamView(long id, String name, long paperId, String paperName, int durationMinutes,
            BigDecimal totalScore, Instant startAt, Instant endAt, String status, long createdBy,
            Long submissionId, String submissionStatus) {}

    /**
     * 单题作答。{@code answerContent} 允许为 {@code null}，表示这道题清空未答——
     * 前端每次提交全部题目，未作答的传 null，因此这里不能加 {@code @NotNull}。
     */
    public record AnswerRequest(@NotNull Long paperQuestionId, JsonNode answerContent) {}

    /** 批量保存答案的请求，整卷一次提交，服务端逐题覆盖。 */
    public record SaveAnswersRequest(@NotEmpty @Valid List<AnswerRequest> answers) {}

    /**
     * 已保存的单题答案，用于刷新或换设备后恢复作答。
     *
     * <p>只有题目 ID 和作答内容，不含标准答案和得分——这个结构会下发给正在答题的学生。
     * {@code answerContent} 可能是 JSON null，表示这道题作答过又被清空。
     */
    public record SavedAnswerView(long paperQuestionId, JsonNode answerContent) {}

    /**
     * 答卷视图。
     *
     * <p>{@code startedAt} 由服务端在开始作答时写入，前端的倒计时以
     * 「考试结束时间」和「startedAt + 试卷时长」中较早的一个为终点。
     *
     * <p>{@code savedAnswers} 是服务端已保存的作答，前端进入答题页时优先用它恢复界面。
     * 这一份才是权威数据：浏览器本地草稿可能来自另一台设备或已被清空，而超时自动交卷
     * 判的恰恰是服务端这一份。
     */
    public record SubmissionView(long id, long examId, String status, Instant startedAt,
            Instant submittedAt, BigDecimal objectiveScore, List<PaperQuestionView> questions,
            List<SavedAnswerView> savedAnswers) {}

    /**
     * 交卷结果。只返回客观题得分：主观题要等教师批阅，
     * 在成绩公布前不能把任何形式的总分透出给学生。
     */
    public record SubmitResult(long id, String status, Instant submittedAt, BigDecimal objectiveScore) {}
}
