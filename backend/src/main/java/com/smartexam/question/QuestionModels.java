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

public final class QuestionModels {
    private QuestionModels() {}

    public enum Type { SINGLE_CHOICE, MULTIPLE_CHOICE, TRUE_FALSE, SHORT_ANSWER }
    public enum Difficulty { EASY, MEDIUM, HARD }

    public record OptionRequest(
            @NotBlank @Size(max=8) String key,
            @NotBlank String content) {}

    public record QuestionRequest(
            @NotNull Type type,
            @NotBlank String stem,
            @NotNull Difficulty difficulty,
            @NotNull JsonNode standardAnswer,
            String explanation,
            @NotNull @DecimalMin(value="0.1") @Digits(integer=5, fraction=1) BigDecimal suggestedScore,
            @NotNull Long knowledgePointId,
            @Valid List<OptionRequest> options) {}

    public record OptionView(long id, String key, String content, int displayOrder) {}

    public record QuestionView(long id, Type type, String stem, Difficulty difficulty,
            JsonNode standardAnswer, String explanation, BigDecimal suggestedScore,
            long knowledgePointId, String knowledgePointName, long createdBy, String status,
            List<OptionView> options) {}
}
