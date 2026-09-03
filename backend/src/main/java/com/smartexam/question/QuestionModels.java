package com.smartexam.question;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * 题库模块的请求与响应模型。
 *
 * <p>刻意区分 {@code XxxRequest} 和 {@code XxxView}：请求只包含客户端可以设置的字段，
 * 像 {@code createdBy}、{@code status} 这类由服务端决定的字段不接受客户端传入，
 * 避免出现「改一下请求体就把题目挂到别的教师名下」这类问题。
 */
public final class QuestionModels {
    private QuestionModels() {}

    /** 题型。SHORT_ANSWER 与 PROGRAMMING 为主观题，只能由教师人工评分。 */
    public enum Type { SINGLE_CHOICE, MULTIPLE_CHOICE, TRUE_FALSE, SHORT_ANSWER, PROGRAMMING }
    /** 难度，仅用于筛选和组卷时的人工判断，不参与分值计算。 */
    public enum Difficulty { EASY, MEDIUM, HARD }

    /**
     * 选项请求。
     *
     * @param key     选项键，保存时统一转成大写，同一题内不可重复
     * @param content 选项正文
     */
    public record OptionRequest(
            @NotBlank @Size(max=8) String key,
            @NotBlank String content) {}

    /**
     * 新增或编辑题目的请求。{@code standardAnswer} 的形态随题型变化，由
     * {@code QuestionService#validate} 逐一校验：
     * <ul>
     *   <li>单选题：长度为 1 的选项键数组，如 {@code ["A"]}</li>
     *   <li>多选题：至少一个选项键的数组，如 {@code ["A","B"]}</li>
     *   <li>判断题：布尔值 {@code true} 或 {@code false}</li>
     *   <li>简答题、编程题：非空字符串，仅作为教师阅卷时的参考答案</li>
     * </ul>
     */
    public record QuestionRequest(
            @NotNull Type type,
            @NotBlank String stem,
            @NotNull Difficulty difficulty,
            /** 关键词标签，英文逗号分隔；仅用于列表展示与检索，不参与判分。 */
            @Size(max=200) String tags,
            @NotNull JsonNode standardAnswer,
            String explanation,
            @NotNull @DecimalMin(value="0.1") @Digits(integer=5, fraction=1) BigDecimal suggestedScore,
            @NotNull Long knowledgePointId,
            @Valid List<OptionRequest> options) {}

    /** 选项响应。{@code displayOrder} 决定展示顺序，从 1 开始。 */
    public record OptionView(long id, String key, String content, int displayOrder) {}

    /**
     * 题目响应。{@code status} 为 {@code ACTIVE} 或 {@code DISABLED}；停用题目仍会出现在
     * 题库列表里（界面需要显示状态并提供启用入口），但不能加入新试卷。
     */
    public record QuestionView(long id, Type type, String stem, Difficulty difficulty, String tags,
            JsonNode standardAnswer, String explanation, BigDecimal suggestedScore,
            long knowledgePointId, String knowledgePointName, long createdBy, String status,
            List<OptionView> options) {}
}
