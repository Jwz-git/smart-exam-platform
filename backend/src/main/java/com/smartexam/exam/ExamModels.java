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

public final class ExamModels {
    private ExamModels() {}

    public record PaperQuestionRequest(@NotNull Long questionId,
            @NotNull @DecimalMin("0.1") @Digits(integer=5, fraction=1) BigDecimal score) {}

    public record PaperRequest(@NotBlank @Size(max=150) String name,
            @Positive int durationMinutes,
            @NotNull @DecimalMin("0.1") @Digits(integer=5, fraction=1) BigDecimal totalScore,
            @NotEmpty @Valid List<PaperQuestionRequest> questions) {}

    public record PaperQuestionView(long id, long questionId, int displayOrder, BigDecimal score,
            String type, String stem, JsonNode options) {}

    public record PaperView(long id, String name, int durationMinutes, BigDecimal totalScore,
            String status, long createdBy, List<PaperQuestionView> questions) {}

    public record ExamRequest(@NotBlank @Size(max=150) String name, @NotNull Long paperId,
            @NotNull Instant startAt, @NotNull @Future Instant endAt) {}

    public record ExamView(long id, String name, long paperId, String paperName, int durationMinutes,
            BigDecimal totalScore, Instant startAt, Instant endAt, String status, long createdBy,
            Long submissionId, String submissionStatus) {}

    public record AnswerRequest(@NotNull Long paperQuestionId, JsonNode answerContent) {}
    public record SaveAnswersRequest(@NotEmpty @Valid List<AnswerRequest> answers) {}

    public record SubmissionView(long id, long examId, String status, Instant startedAt,
            Instant submittedAt, BigDecimal objectiveScore, List<PaperQuestionView> questions) {}

    public record SubmitResult(long id, String status, Instant submittedAt, BigDecimal objectiveScore) {}
}
