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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 试卷、考试、答卷与客观题判分的业务规则。整个项目最核心的一致性要求集中在这里。
 *
 * <p>关键规则：
 * <ol>
 *   <li>组卷时把题干、选项、答案写入 {@code paper_question} 快照，之后改题库不影响历史试卷与成绩；</li>
 *   <li>一名学生对同一场考试最多一份有效答卷，由数据库唯一约束兜底；</li>
 *   <li>交卷与判分在同一事务内完成，并先加行锁，重复提交不会二次计分；</li>
 *   <li>单选、判断答对得满分否则 0 分；多选必须与标准答案集合完全一致才得分；</li>
 *   <li>简答题与编程题不自动判分，等教师批阅。</li>
 * </ol>
 */
@Service
public class ExamService {
    /** 主观题题型，定义见 {@link GradingModels#SUBJECTIVE_TYPES}；判分与阅卷共用同一份，避免两处口径不一致。 */
    private static final Set<String> SUBJECTIVE_TYPES = GradingModels.SUBJECTIVE_TYPES;

    private final ExamRepository repository;
    private final QuestionRepository questions;

    public ExamService(ExamRepository repository, QuestionRepository questions) {
        this.repository = repository; this.questions = questions;
    }

    /** 查询指定教师创建的全部试卷。 */
    public List<PaperView> papers(long teacherId) { return repository.findPapers(teacherId); }

    /** 查询试卷详情并校验归属，越权返回 403。 */
    public PaperView paper(long id, long teacherId) {
        PaperView paper = requirePaper(id);
        requireOwner(paper.createdBy(), teacherId, "不能访问其他教师的试卷");
        return paper;
    }

    /**
     * 创建试卷草稿。
     *
     * <p>四道校验按「先便宜后昂贵」的顺序排列：先算分值合计（纯内存），
     * 再逐题查库校验重复、归属和启用状态。用 {@code compareTo} 而不是 {@code equals}
     * 比较 BigDecimal，因为 {@code 40} 和 {@code 40.0} 精度不同但数值相等。
     */
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

    /** 发布试卷。只有草稿可发布，且必须至少有一道题。 */
    @Transactional
    public PaperView publishPaper(long id, long teacherId) {
        PaperView paper = paper(id, teacherId);
        if (!"DRAFT".equals(paper.status())) throw conflict("INVALID_STATE_TRANSITION", "只有草稿试卷可以发布");
        if (paper.questions().isEmpty()) bad("EMPTY_PAPER", "试卷至少包含一道题");
        repository.publishPaper(id);
        return repository.findPaper(id).orElseThrow();
    }

    /** 教师视角的考试列表：本人创建的全部考试，含草稿。 */
    public List<ExamView> teacherExams(long teacherId) { return repository.findTeacherExams(teacherId); }

    /**
     * 学生视角的考试列表：所有已发布考试，附带本人答卷状态。
     *
     * <p>不做班级或选课过滤——MVP 明确不建设班级模块，所有正常状态的学生都可参加已发布考试。
     */
    public List<ExamView> studentExams(long studentId) { return repository.findStudentExams(studentId); }

    /** 创建考试草稿。只能引用本人已发布的试卷，且结束时间必须晚于开始时间。 */
    @Transactional
    public ExamView createExam(ExamRequest request, long teacherId) {
        if (!request.endAt().isAfter(request.startAt())) bad("INVALID_EXAM_WINDOW", "考试结束时间必须晚于开始时间");
        PaperView paper = paper(request.paperId(), teacherId);
        if (!"PUBLISHED".equals(paper.status())) bad("PAPER_NOT_PUBLISHED", "只能使用已发布试卷创建考试");
        try { return repository.findExam(repository.createExam(request, teacherId), null).orElseThrow(); }
        catch (DataIntegrityViolationException exception) { throw conflict("EXAM_CONFLICT", "考试数据发生冲突"); }
    }

    /**
     * 发布考试。
     *
     * <p>这里再查一次结束时间是否已过：创建时 {@code @Future} 只保证「创建那一刻」在未来，
     * 草稿放置一段时间后可能已经过期，此时发布出去等于发一场谁都答不了的考试。
     */
    @Transactional
    public ExamView publishExam(long id, long teacherId) {
        ExamView exam = requireExam(id, null);
        requireOwner(exam.createdBy(), teacherId, "不能发布其他教师的考试");
        if (!"DRAFT".equals(exam.status())) throw conflict("INVALID_STATE_TRANSITION", "只有草稿考试可以发布");
        if (!exam.endAt().isAfter(Instant.now())) bad("EXAM_ALREADY_ENDED", "结束时间已过，不能发布考试");
        repository.publishExam(id);
        return repository.findExam(id, null).orElseThrow();
    }

    /**
     * 查询考试详情。
     *
     * <p>学生访问未发布的考试时返回 404 而不是 403：草稿考试的存在本身就不该对学生可见，
     * 用 404 可以避免通过接口探测「有哪些考试正在准备」。
     */
    public ExamView exam(long id, long userId, boolean teacher) {
        ExamView exam = requireExam(id, teacher ? null : userId);
        if (teacher) requireOwner(exam.createdBy(), userId, "不能访问其他教师的考试");
        else if (!"PUBLISHED".equals(exam.status())) throw notFound("EXAM_NOT_FOUND", "考试不存在");
        return exam;
    }

    /**
     * 开始作答，幂等。
     *
     * <p>已有未交卷答卷时直接返回它，这样刷新页面或换设备都能继续原来的答卷，
     * 也不会重置倒计时的起点；已交卷则返回 409，避免重考。
     *
     * <p>即使先查过一次，插入仍可能因并发撞上唯一约束（同一学生同时点两次「进入考试」），
     * 所以这里还要接住 {@link DataIntegrityViolationException} 转成 409。
     */
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
        try { return repository.findSubmission(repository.createSubmission(examId, studentId, Instant.now())).orElseThrow(); }
        catch (DataIntegrityViolationException exception) { throw conflict("SUBMISSION_CONFLICT", "答卷已存在，请刷新后重试"); }
    }

    /**
     * 保存答案。
     *
     * <p>三重校验：答卷属于本人、答卷仍在作答中、考试仍在开放时间内。
     * 逐题校验 {@code paperQuestionId} 确实属于当前答卷，防止把答案写到别人的卷子上。
     *
     * <p>整个方法在一个事务里，中途任何一题校验失败都会整体回滚，
     * 不会留下「前几题存进去、后几题没存」的半截状态。
     */
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

    /**
     * 交卷并判客观题。
     *
     * <p>第一行就先取行锁再读状态，顺序不能颠倒：如果先读后锁，两个并发请求可能都读到
     * {@code IN_PROGRESS} 然后都去判分，导致重复计分。加锁后第二个请求会等待，
     * 拿到锁时状态已变成 {@code SUBMITTED}，于是返回 409。
     */
    @Transactional
    public SubmitResult submit(long id, long studentId) {
        repository.lockSubmission(id);
        SubmissionView submission = requireSubmission(id, studentId);
        if (!"IN_PROGRESS".equals(submission.status())) throw conflict("SUBMISSION_ALREADY_SUBMITTED", "答卷已经提交");
        ExamView exam = requireExam(submission.examId(), studentId);
        if (Instant.now().isBefore(exam.startAt())) bad("EXAM_NOT_STARTED", "考试尚未开始");
        return scoreAndSubmit(id);
    }

    /**
     * 扫描已到截止时间但仍在作答的答卷，自动交卷并判分。
     *
     * <p>放在服务端而不是靠前端倒计时触发：学生可能直接关掉浏览器，
     * 那样答卷会永远停在「作答中」，教师也就永远无法完成阅卷和公布成绩。
     *
     * <p>定时触发在 {@link ExamScheduler} 里，本方法只负责一次扫描，因此测试可以直接调用它
     * 而不必等真实的轮询间隔。
     *
     * <p>逐条重新加锁并复查状态，避免与学生本人的手动交卷抢同一份答卷。
     * 已知不足：整批共用一个事务，条数很多时锁持有时间偏长，规模变大后应改为逐条独立事务。
     */
    @Transactional
    public void autoSubmitExpired() {
        Instant now = Instant.now();
        for (long id : repository.findExpiredInProgressSubmissions(now)) {
            repository.lockSubmission(id);
            SubmissionView submission = repository.findSubmission(id).orElseThrow();
            if ("IN_PROGRESS".equals(submission.status())) scoreAndSubmit(id);
        }
    }

    /**
     * 判分并落库。手动交卷和超时自动交卷共用这一段，保证两条路径的判分结果完全一致。
     *
     * <p>主观题也会写入一条 0 分记录，作为占位；教师批阅时再覆盖。
     * 「是否已批阅」以 {@code graded_at} 是否为空判断，不能靠分数是不是 0。
     */
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

    /**
     * 比对标准答案与学生作答。
     *
     * <p>选择题按集合比较，忽略选项顺序和大小写差异；多选必须完全一致，少选或多选均不得分。
     * 判断题直接比较布尔值。未作答（null 或 JSON null）一律不得分。
     */
    private boolean equalAnswer(JsonNode expected, JsonNode actual) {
        if (actual == null || actual.isNull()) return false;
        if (expected.isArray()) return stringSet(expected).equals(stringSet(actual));
        return expected.equals(actual);
    }

    /** 把选项键数组转成规范化集合：去空格、转大写，使 {@code ["a","B"]} 与 {@code ["A","b"]} 等价。 */
    private Set<String> stringSet(JsonNode node) {
        Set<String> values = new HashSet<>();
        if (!node.isArray()) return values;
        node.forEach(value -> values.add(value.asText().trim().toUpperCase(Locale.ROOT)));
        return values;
    }

    private boolean isObjective(String type) { return !SUBJECTIVE_TYPES.contains(type); }
    /** 校验考试处于可作答状态：已发布、已开始、未截止。三种失败给出不同的错误码便于前端提示。 */
    private void ensureOpen(ExamView exam) {
        Instant now = Instant.now();
        if (!"PUBLISHED".equals(exam.status())) bad("EXAM_NOT_PUBLISHED", "考试尚未发布");
        if (now.isBefore(exam.startAt())) bad("EXAM_NOT_STARTED", "考试尚未开始");
        if (!now.isBefore(exam.endAt())) bad("EXAM_ENDED", "考试已经结束");
    }

    /** 取试卷，不存在则 404。 */
    private PaperView requirePaper(long id) { return repository.findPaper(id).orElseThrow(() -> notFound("PAPER_NOT_FOUND", "试卷不存在")); }
    /** 取考试，不存在则 404。{@code studentId} 为 null 表示不需要附带答卷状态（教师视角）。 */
    private ExamView requireExam(long id, Long studentId) { return repository.findExam(id, studentId).orElseThrow(() -> notFound("EXAM_NOT_FOUND", "考试不存在")); }
    /** 取答卷并校验归属，确保学生只能操作自己的答卷。 */
    private SubmissionView requireSubmission(long id, long studentId) {
        SubmissionView submission = repository.findSubmission(id).orElseThrow(() -> notFound("SUBMISSION_NOT_FOUND", "答卷不存在"));
        requireOwner(repository.submissionOwner(id), studentId, "不能访问其他学生的答卷"); return submission;
    }
    /** 查询该学生在这场考试里是否已有答卷。 */
    private OptionalSubmission existing(long exam, long student) { return new OptionalSubmission(repository.findSubmissionId(exam, student).orElse(null)); }
    /** 包装可能为空的答卷 ID，避免在调用处直接与 null 打交道。 */
    private record OptionalSubmission(Long id) {}

    /** 资源归属校验：属主与当前用户不一致时返回 403。 */
    private void requireOwner(long owner, long user, String message) { if (owner != user) throw new DomainException(HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN", message); }
    private void bad(String code, String message) { throw new DomainException(HttpStatus.BAD_REQUEST, code, message); }
    private DomainException conflict(String code, String message) { return new DomainException(HttpStatus.CONFLICT, code, message); }
    private DomainException notFound(String code, String message) { return new DomainException(HttpStatus.NOT_FOUND, code, message); }
}
