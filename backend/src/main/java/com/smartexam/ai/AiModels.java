package com.smartexam.ai;

import com.smartexam.question.QuestionModels.Difficulty;
import com.smartexam.question.QuestionModels.QuestionRequest;
import com.smartexam.question.QuestionModels.Type;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** AI 辅助出题的请求与响应模型。 */
public final class AiModels {
    private AiModels() {}

    /**
     * 生成题目草稿的请求。
     *
     * <p>题型、难度、知识点和分值都由教师指定而不是让模型决定：这四项决定了题目能不能通过
     * 现有业务校验，交给模型只会增加失败率，也让「生成的和我要的不一样」变成常态。
     * 模型只负责题干、选项、答案和解析这些真正需要创作的部分。
     *
     * @param count       生成数量，1–5。上限刻意设得低：教师要逐题确认，一次给太多反而没人看
     * @param requirement 补充要求，例如「结合 Spring 注解」「不要考 API 记忆」
     */
    public record DraftRequest(
            @NotNull Long knowledgePointId,
            @NotNull Type type,
            @NotNull Difficulty difficulty,
            @Min(1) @Max(5) Integer count,
            @Size(max = 500) String requirement,
            @DecimalMin("0.1") @Digits(integer = 5, fraction = 1) BigDecimal suggestedScore) {

        /** 生成数量的默认值。不传时只生成一道，最快看到结果。 */
        public int normalizedCount() { return count == null ? 1 : count; }

        /** 分值默认 10 分，与验收前置数据里的每题分值一致。 */
        public BigDecimal normalizedScore() { return suggestedScore == null ? BigDecimal.TEN : suggestedScore; }
    }

    /**
     * 生成结果。
     *
     * <p>{@code drafts} 直接复用 {@link QuestionRequest}：教师确认后前端原样 POST 到题目新增接口，
     * 不需要再做一次字段映射，也保证草稿一定走完整的业务校验。草稿本身不入库。
     *
     * @param warnings 被丢弃的草稿及原因。模型偶尔会生成不合规的题（例如只给一个选项），
     *                 这里如实告知而不是静默丢掉——教师需要知道「要了 3 道只回来 2 道」
     */
    public record DraftResponse(String protocol, String model, List<QuestionRequest> drafts, List<String> warnings) {}
}
