package com.smartexam.practice;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 错题本与错题重练的请求响应模型。
 *
 * <p>本模块只服务学生本人，全部接口挂在 {@code /api/v1/my/**} 下（该前缀在
 * {@code SecurityConfig} 里限定为 {@code STUDENT}，且路径里不带学生 ID）。
 *
 * <p>两个视图的字段刻意不同，这是本功能最重要的设计决定：
 * <ul>
 *   <li><b>错题本</b>（{@link WrongQuestionView}）用于复习，带标准答案、解析和教师评语。
 *       这些内容学生本来就有权看到——错题只来自<b>已公布成绩</b>的考试，成绩公布后
 *       答案与解析对本人是可见的；</li>
 *   <li><b>练习集</b>（{@link PracticeQuestionView}）用于重做，<b>刻意不含标准答案、解析，
 *       也不含上次的错误作答</b>。否则重练就退化成抄一遍答案，练不出任何东西。
 *       答案只在提交之后随判分结果返回。</li>
 * </ul>
 *
 * <p>另一条硬约束：重练<b>绝不改动答卷和成绩</b>。练习记录写在独立的
 * {@code practice_attempt} 表里，{@code submission_answer} 一个字段都不动——
 * 已公布的成绩不能被学生自己的练习改写。
 */
public final class PracticeModels {
    private PracticeModels() {}

    /**
     * 错题本里的一道题。
     *
     * @param answerId       答案记录 ID，仅作为列表的稳定键
     * @param paperQuestionId 试卷题目 ID，重练时用它定位（错题指向的是考试当时的那道题快照）
     * @param score          本人在这道题上的得分；{@code 0} 与部分得分都会进错题本
     * @param maxScore       这道题的满分
     * @param blank          是否未作答。未作答与答错都算错题，但界面上要能区分
     * @param subjective     是否主观题。主观题无法自动判分，因此只能复习、不能重练
     * @param practiceCount  已重练次数
     * @param lastCorrect    最近一次重练是否正确；从未重练时为 {@code null}
     * @param mastered       是否已掌握，判据是「最近一次重练正确」
     */
    public record WrongQuestionView(long answerId, long submissionId, long paperQuestionId, long examId,
            String examName, int displayOrder, String type, String stem, JsonNode options,
            BigDecimal maxScore, BigDecimal score, JsonNode myAnswer, JsonNode standardAnswer,
            String explanation, String gradingComment, Instant submittedAt, boolean subjective,
            boolean blank, int practiceCount, Boolean lastCorrect, Instant lastPracticedAt, boolean mastered) {}

    /**
     * 错题本。
     *
     * @param objectiveCount     其中可重练的客观题数量
     * @param subjectiveCount    其中只能复习的主观题数量
     * @param masteredCount      已掌握的题数（最近一次重练正确）
     * @param practiceBatchSize  一次重练默认取几道，来自系统设置，前端据此显示按钮文案
     */
    public record WrongBookView(int total, int objectiveCount, int subjectiveCount, int masteredCount,
            int practiceBatchSize, List<WrongQuestionView> items) {}

    /**
     * 练习集里的一道题。
     *
     * <p>字段刻意比错题本少：没有标准答案、没有解析、也没有上次的错误作答。
     */
    public record PracticeQuestionView(long paperQuestionId, String type, String stem, JsonNode options,
            BigDecimal maxScore, String examName, int practiceCount) {}

    /** 一组练习题。{@code size} 与 {@code questions.size()} 相同，单独给出是为了前端少写一次判空。 */
    public record PracticeSetView(int size, List<PracticeQuestionView> questions) {}

    /** 一道题的练习作答。{@code answerContent} 允许为 {@code null}，表示这道题没作答，判错。 */
    public record PracticeAnswerRequest(@NotNull Long paperQuestionId, JsonNode answerContent) {}

    /** 提交一组练习作答。 */
    public record PracticeSubmitRequest(
            @NotEmpty(message = "请至少提交一道题的作答")
            @Size(max = 50, message = "一次最多提交 50 道题")
            @Valid List<PracticeAnswerRequest> answers) {}

    /**
     * 一道练习题的判分结果。
     *
     * <p>到这一步才返回标准答案和解析：先答后看，重练才有意义。
     */
    public record PracticeAnswerResultView(long paperQuestionId, String stem, boolean correct,
            JsonNode myAnswer, JsonNode standardAnswer, String explanation) {}

    /**
     * 一次练习的整体结果。
     *
     * @param accuracy 本次正确率百分比，保留一位小数
     */
    public record PracticeResultView(int total, int correctCount, BigDecimal accuracy,
            List<PracticeAnswerResultView> answers) {}
}
