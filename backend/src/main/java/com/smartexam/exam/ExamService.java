package com.smartexam.exam;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartexam.common.DomainException;
import com.smartexam.exam.ExamModels.*;
import com.smartexam.exam.ExamRepository.AnswerRow;
import com.smartexam.question.QuestionModels.QuestionView;
import com.smartexam.question.QuestionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamService {
    private final ExamRepository repository;
    private final QuestionRepository questions;

    public ExamService(ExamRepository repository, QuestionRepository questions) {
        this.repository = repository; this.questions = questions;
    }

    public List<PaperView> papers(long teacherId) { return repository.findPapers(teacherId); }

    public PaperView paper(long id, long teacherId) {
        PaperView paper = requirePaper(id);
        requireOwner(paper.createdBy(), teacherId, "不能访问其他教师的试卷");
        return paper;
    }

    @Transactional
    public PaperView createPaper(PaperRequest request, long teacherId) {
        BigDecimal sum = request.questions().stream().map(PaperQuestionRequest::score).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(request.totalScore()) != 0) bad("PAPER_SCORE_MISMATCH", "题目分值合计必须等于试卷总分");
        Set<Long> ids = new HashSet<>();
        for (PaperQuestionRequest item : request.questions()) {
            if (!ids.add(item.questionId())) bad("DUPLICATE_PAPER_QUESTION", "同一道题不能重复加入试卷");
            QuestionView question = questions.findById(item.questionId())
                    .orElseThrow(() -> notFound("QUESTION_NOT_FOUND", "题目不存在"));
            requireOwner(question.createdBy(), teacherId, "不能使用其他教师的题目");
            if (!"ACTIVE".equals(question.status())) bad("QUESTION_DISABLED", "停用题目不能加入试卷");
        }
        try { return repository.findPaper(repository.createPaper(request, teacherId)).orElseThrow(); }
        catch (DataIntegrityViolationException exception) { throw conflict("PAPER_CONFLICT", "试卷数据发生冲突"); }
    }

    @Transactional
    public PaperView publishPaper(long id, long teacherId) {
        PaperView paper = paper(id, teacherId);
        if (!"DRAFT".equals(paper.status())) throw conflict("INVALID_STATE_TRANSITION", "只有草稿试卷可以发布");
        if (paper.questions().isEmpty()) bad("EMPTY_PAPER", "试卷至少包含一道题");
        repository.publishPaper(id);
        return repository.findPaper(id).orElseThrow();
    }

    public List<ExamView> teacherExams(long teacherId) { return repository.findTeacherExams(teacherId); }
    public List<ExamView> studentExams(long studentId) { return repository.findStudentExams(studentId); }

    @Transactional
    public ExamView createExam(ExamRequest request, long teacherId) {
        if (!request.endAt().isAfter(request.startAt())) bad("INVALID_EXAM_WINDOW", "考试结束时间必须晚于开始时间");
        PaperView paper = paper(request.paperId(), teacherId);
        if (!"PUBLISHED".equals(paper.status())) bad("PAPER_NOT_PUBLISHED", "只能使用已发布试卷创建考试");
        try { return repository.findExam(repository.createExam(request, teacherId), null).orElseThrow(); }
        catch (DataIntegrityViolationException exception) { throw conflict("EXAM_CONFLICT", "考试数据发生冲突"); }
    }

    @Transactional
    public ExamView publishExam(long id, long teacherId) {
        ExamView exam = requireExam(id, null);
        requireOwner(exam.createdBy(), teacherId, "不能发布其他教师的考试");
        if (!"DRAFT".equals(exam.status())) throw conflict("INVALID_STATE_TRANSITION", "只有草稿考试可以发布");
        if (!exam.endAt().isAfter(Instant.now())) bad("EXAM_ALREADY_ENDED", "结束时间已过，不能发布考试");
        repository.publishExam(id);
        return repository.findExam(id, null).orElseThrow();
    }

    public ExamView exam(long id, long userId, boolean teacher) {
        ExamView exam = requireExam(id, teacher ? null : userId);
        if (teacher) requireOwner(exam.createdBy(), userId, "不能访问其他教师的考试");
        else if (!"PUBLISHED".equals(exam.status())) throw notFound("EXAM_NOT_FOUND", "考试不存在");
        return exam;
    }

    @Transactional
    public SubmissionView start(long examId, long studentId) {
        ExamView exam = requireExam(examId, studentId);
        ensureOpen(exam);
        OptionalSubmission existing = existing(examId, studentId);
        if (existing.id != null) {
            SubmissionView submission = repository.findSubmission(existing.id).orElseThrow();
            if (!"IN_PROGRESS".equals(submission.status())) throw conflict("SUBMISSION_ALREADY_SUBMITTED", "该考试已交卷，不能重复开始");
            return submission;
        }
        try { return repository.findSubmission(repository.createSubmission(examId, studentId)).orElseThrow(); }
        catch (DataIntegrityViolationException exception) { throw conflict("SUBMISSION_CONFLICT", "答卷已存在，请刷新后重试"); }
    }

    @Transactional
    public SubmissionView save(long id, SaveAnswersRequest request, long studentId) {
        SubmissionView submission = requireSubmission(id, studentId);
        if (!"IN_PROGRESS".equals(submission.status())) throw conflict("SUBMISSION_ALREADY_SUBMITTED", "已交卷，不能继续修改答案");
        ensureOpen(requireExam(submission.examId(), studentId));
        Set<Long> ids = new HashSet<>();
        for (AnswerRequest answer : request.answers()) {
            if (!ids.add(answer.paperQuestionId())) bad("DUPLICATE_ANSWER", "同一道题不能重复提交答案");
            if (!repository.questionBelongsToSubmission(id, answer.paperQuestionId())) bad("INVALID_PAPER_QUESTION", "题目不属于当前答卷");
            repository.saveAnswer(id, answer);
        }
        return repository.findSubmission(id).orElseThrow();
    }

    @Transactional
    public SubmitResult submit(long id, long studentId) {
        repository.lockSubmission(id);
        SubmissionView submission = requireSubmission(id, studentId);
        if (!"IN_PROGRESS".equals(submission.status())) throw conflict("SUBMISSION_ALREADY_SUBMITTED", "答卷已经提交");
        ExamView exam = requireExam(submission.examId(), studentId);
        if (Instant.now().isBefore(exam.startAt())) bad("EXAM_NOT_STARTED", "考试尚未开始");
        return scoreAndSubmit(id);
    }

    @Scheduled(fixedDelayString = "${app.exam.auto-submit-interval-ms:30000}")
    @Transactional
    public void autoSubmitExpired() {
        Instant now = Instant.now();
        for (long id : repository.findExpiredInProgressSubmissions(now)) {
            repository.lockSubmission(id);
            SubmissionView submission = repository.findSubmission(id).orElseThrow();
            if ("IN_PROGRESS".equals(submission.status())) scoreAndSubmit(id);
        }
    }

    private SubmitResult scoreAndSubmit(long id) {
        BigDecimal objective = BigDecimal.ZERO;
        for (AnswerRow row : repository.answersForScoring(id)) {
            BigDecimal score = isObjective(row.type()) && equalAnswer(row.expected(), row.actual()) ? row.score() : BigDecimal.ZERO;
            repository.scoreAnswer(id, row.paperQuestionId(), score);
            if (isObjective(row.type())) objective = objective.add(score);
        }
        Instant submittedAt = Instant.now();
        repository.submit(id, objective, submittedAt);
        return new SubmitResult(id, "SUBMITTED", submittedAt, objective);
    }

    private boolean equalAnswer(JsonNode expected, JsonNode actual) {
        if (actual == null || actual.isNull()) return false;
        if (expected.isArray()) return stringSet(expected).equals(stringSet(actual));
        return expected.equals(actual);
    }

    private Set<String> stringSet(JsonNode node) {
        Set<String> values = new HashSet<>();
        if (!node.isArray()) return values;
        node.forEach(value -> values.add(value.asText().trim().toUpperCase(Locale.ROOT)));
        return values;
    }

    private boolean isObjective(String type) { return !"SHORT_ANSWER".equals(type); }
    private void ensureOpen(ExamView exam) {
        Instant now = Instant.now();
        if (!"PUBLISHED".equals(exam.status())) bad("EXAM_NOT_PUBLISHED", "考试尚未发布");
        if (now.isBefore(exam.startAt())) bad("EXAM_NOT_STARTED", "考试尚未开始");
        if (!now.isBefore(exam.endAt())) bad("EXAM_ENDED", "考试已经结束");
    }

    private PaperView requirePaper(long id) { return repository.findPaper(id).orElseThrow(() -> notFound("PAPER_NOT_FOUND", "试卷不存在")); }
    private ExamView requireExam(long id, Long studentId) { return repository.findExam(id, studentId).orElseThrow(() -> notFound("EXAM_NOT_FOUND", "考试不存在")); }
    private SubmissionView requireSubmission(long id, long studentId) {
        SubmissionView submission = repository.findSubmission(id).orElseThrow(() -> notFound("SUBMISSION_NOT_FOUND", "答卷不存在"));
        requireOwner(repository.submissionOwner(id), studentId, "不能访问其他学生的答卷"); return submission;
    }
    private OptionalSubmission existing(long exam, long student) { return new OptionalSubmission(repository.findSubmissionId(exam, student).orElse(null)); }
    private record OptionalSubmission(Long id) {}
    private void requireOwner(long owner, long user, String message) { if (owner != user) throw new DomainException(HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN", message); }
    private void bad(String code, String message) { throw new DomainException(HttpStatus.BAD_REQUEST, code, message); }
    private DomainException conflict(String code, String message) { return new DomainException(HttpStatus.CONFLICT, code, message); }
    private DomainException notFound(String code, String message) { return new DomainException(HttpStatus.NOT_FOUND, code, message); }
}
