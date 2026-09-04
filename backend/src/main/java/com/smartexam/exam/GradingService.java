package com.smartexam.exam;

import com.smartexam.common.DomainException;
import com.smartexam.common.SettingsCatalog;
import com.smartexam.common.SettingsStore;
import com.smartexam.exam.GradingModels.AnswerDetailView;
import com.smartexam.exam.GradingModels.ExamResultsView;
import com.smartexam.exam.GradingModels.GradingBoardView;
import com.smartexam.exam.GradingModels.GradingItemView;
import com.smartexam.exam.GradingModels.MyResultItemView;
import com.smartexam.exam.GradingModels.RankingItemView;
import com.smartexam.exam.GradingModels.ScoreRequest;
import com.smartexam.exam.GradingModels.SubmissionDetailView;
import com.smartexam.exam.GradingRepository.AnswerContext;
import com.smartexam.exam.GradingRepository.ExamHeader;
import com.smartexam.exam.GradingRepository.MyResultRow;
import com.smartexam.exam.GradingRepository.RankingRow;
import com.smartexam.exam.GradingRepository.SubmissionHeader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 主观题阅卷、成绩汇总、排名与成绩公布的业务规则。
 *
 * <p>四条核心规则：
 * <ol>
 *   <li>只有主观题能人工评分，分数不得超过该题在本试卷中的满分；客观题由系统判定，教师改不了；</li>
 *   <li>每次评分后立即重算 {@code subjective_score} 与 {@code total_score}；一份卷子的主观题全部评完即为已评分；</li>
 *   <li>公布成绩要求「无人仍在作答」且「全场主观题已批完」，公布后评分冻结、考试关闭；</li>
 *   <li>排名采用竞赛排名（{@code 1、2、2、4}），只统计已评完的有效答卷。</li>
 *   <li>及格线占试卷总分的比例来自系统设置（默认 60%），改动后立刻生效，历史分数不变。</li>
 * </ol>
 *
 * <p>字段可见性是本类第二个职责，规则见 {@link GradingModels}：教师看全部，学生公布前只能看到自己的作答，
 * 公布后才能看到本人得分、标准答案、解析、评语和名次。裁剪一律在服务端完成——
 * 前端不显示不等于没下发，浏览器里能看到的东西就等于已经泄露。
 */
@Service
public class GradingService {
    private final GradingRepository repository;
    private final SettingsStore settings;

    public GradingService(GradingRepository repository, SettingsStore settings) {
        this.repository = repository; this.settings = settings;
    }

    /**
     * 及格线占试卷总分的比例，默认 60%（课程未规定及格线，取通用做法）。
     *
     * <p>原先是本类的一个 {@code public static final} 常量，现在改为运行时读取系统设置：
     * 管理员在系统设置页改及格线后，成绩页与统计分析页立刻按新线重算，不需要重启。
     * 统计分析模块调用同一个方法，因此两个页面不可能出现两条不同的及格线。
     */
    public BigDecimal passRatio() { return settings.passRatio(); }

    /**
     * 教师阅卷面板：本场考试的全部答卷、每份的主观题总数与未评分数量。
     *
     * <p>{@code pendingCount} 汇总的是「已交卷答卷」里未评分的主观题；仍在作答的答卷不计入，
     * 否则教师会看到一个永远降不到 0 的待批数字，也就无法判断什么时候可以公布成绩。
     */
    public GradingBoardView board(long examId, long teacherId) {
        ExamHeader exam = requireExam(examId);
        requireOwner(exam.createdBy(), teacherId, "不能阅卷其他教师的考试");
        List<GradingItemView> items = repository.findGradingItems(examId);
        return new GradingBoardView(exam.id(), exam.name(), exam.paperTotalScore(), exam.status(),
                isPublished(exam.status()), items.size(), repository.inProgressCount(examId),
                repository.examPendingCount(examId), items);
    }

    /**
     * 给一道主观题评分并写入评语，随后重算该答卷的总分。
     *
     * <p>五道校验的顺序是「先存在、再归属、再题型、再状态、最后分值」：
     * 先用 404 挡掉不存在的答案，再用 403 挡掉别人考试的答卷，这样不会通过错误码差异
     * 泄露「这个 ID 是否真实存在」之外的信息。
     *
     * <p>返回整份答卷详情而不是单题结果：教师界面右侧要同步刷新总分和剩余待批数量，
     * 返回全量可以省掉一次额外请求。
     */
    @Transactional
    public SubmissionDetailView score(long answerId, ScoreRequest request, long teacherId) {
        AnswerContext answer = repository.findAnswerContext(answerId)
                .orElseThrow(() -> notFound("ANSWER_NOT_FOUND", "答案记录不存在"));
        requireOwner(answer.examCreatedBy(), teacherId, "不能阅卷其他教师的考试");
        if (!GradingModels.SUBJECTIVE_TYPES.contains(answer.type())) bad("NOT_SUBJECTIVE", "客观题由系统自动判分，不能人工评分");
        if ("IN_PROGRESS".equals(answer.submissionStatus())) bad("SUBMISSION_NOT_SUBMITTED", "答卷尚未交卷，不能评分");
        if (isPublished(answer.examStatus())) throw conflict("INVALID_STATE_TRANSITION", "成绩已公布，不能再修改评分");
        if (request.score().compareTo(answer.maxScore()) > 0) bad("SCORE_EXCEEDS_MAX", "得分不能超过该题满分 " + answer.maxScore());
        repository.scoreSubjective(answerId, request.score(),
                request.comment() == null || request.comment().isBlank() ? null : request.comment().trim(),
                teacherId, Instant.now());
        refreshTotals(answer.submissionId());
        return detail(answer.submissionId(), teacherId, true);
    }

    /**
     * 答卷详情。教师阅卷、学生答题回看和学生成绩查询共用这一个方法，差别只在字段裁剪。
     *
     * <p>学生走这里时只允许读自己的答卷（403 兜底），且成绩公布前看不到任何分数、
     * 标准答案、解析、评语和名次——这正是验收用例 5、6 要验证的隔离。
     */
    public SubmissionDetailView detail(long submissionId, long userId, boolean teacher) {
        SubmissionHeader header = repository.findSubmissionHeader(submissionId)
                .orElseThrow(() -> notFound("SUBMISSION_NOT_FOUND", "答卷不存在"));
        if (teacher) requireOwner(header.examCreatedBy(), userId, "不能查看其他教师考试的答卷");
        else requireOwner(header.studentId(), userId, "不能查看其他学生的答卷");
        boolean published = isPublished(header.examStatus());
        // 教师随时可见全部字段；学生只有在成绩公布后才能看到分数与答案。
        boolean full = teacher || published;
        List<AnswerDetailView> answers = repository.findAnswers(submissionId).stream()
                .map(answer -> full ? answer : mask(answer)).toList();
        Integer rank = full ? rankOf(header.examId(), submissionId) : null;
        return new SubmissionDetailView(header.id(), header.examId(), header.examName(), header.studentId(),
                header.studentName(), header.status(), header.startedAt(), header.submittedAt(),
                header.paperTotalScore(), full ? header.objectiveScore() : null,
                full ? header.subjectiveScore() : null, full ? header.totalScore() : null,
                published, rank, answers);
    }

    /**
     * 公布成绩。
     *
     * <p>三个前置条件缺一不可：至少有一份答卷、无人仍在作答、全场主观题已批完。
     * 少了任何一条，排名和平均分都会把半成品算进去。
     *
     * <p>副作用是考试同时关闭：状态变成 {@code RESULTS_PUBLISHED} 后
     * {@code ExamService#ensureOpen} 不再放行，学生无法再开始或继续作答。
     * 这是有意的——成绩已经公布，再让人进场答题就没法解释排名是怎么算出来的。
     */
    @Transactional
    public ExamResultsView publishResults(long examId, long teacherId) {
        ExamHeader exam = requireExam(examId);
        requireOwner(exam.createdBy(), teacherId, "不能公布其他教师考试的成绩");
        if (isPublished(exam.status())) throw conflict("INVALID_STATE_TRANSITION", "成绩已经公布");
        if (!"PUBLISHED".equals(exam.status())) throw conflict("INVALID_STATE_TRANSITION", "只有已发布的考试可以公布成绩");
        if (repository.findGradingItems(examId).isEmpty()) bad("NO_SUBMISSION", "还没有任何答卷，无法公布成绩");
        if (repository.inProgressCount(examId) > 0) bad("SUBMISSION_IN_PROGRESS", "仍有学生在作答，不能公布成绩");
        if (repository.examPendingCount(examId) > 0) bad("GRADING_NOT_FINISHED", "还有主观题未评分，不能公布成绩");
        // 已批完的答卷统一置为 GRADED，让 submission.status 与「是否计入排名」保持一致。
        repository.markFullyGraded(examId);
        repository.publishResults(examId, Instant.now());
        return results(examId, teacherId);
    }

    /**
     * 教师视角的成绩统计与完整排名。
     *
     * <p>公布之前也允许查看：教师需要先看一眼分布是否正常再决定公布。学生看到的是
     * {@link #myResults} 的裁剪版本，只有本人名次和总人数。
     */
    public ExamResultsView results(long examId, long teacherId) {
        ExamHeader exam = requireExam(examId);
        requireOwner(exam.createdBy(), teacherId, "不能查看其他教师考试的成绩");
        List<RankingItemView> rankings = rank(repository.findRankingRows(examId));
        List<BigDecimal> scores = rankings.stream().map(RankingItemView::totalScore).toList();
        BigDecimal pass = exam.paperTotalScore().multiply(passRatio());
        return new ExamResultsView(exam.id(), exam.name(), exam.paperTotalScore(), exam.status(),
                isPublished(exam.status()), exam.resultsPublishedAt(), rankings.size(), average(scores),
                scores.isEmpty() ? null : scores.get(0), scores.isEmpty() ? null : scores.get(scores.size() - 1),
                ratio(scores.stream().filter(score -> score.compareTo(pass) >= 0).count(), scores.size()), rankings);
    }

    /**
     * 学生本人的已公布成绩列表。
     *
     * <p>返回 {@code rank} 与 {@code totalCount} 而不是完整名单：学生能知道「我第 2 名、共 4 人」，
     * 但拿不到任何其他学生的姓名和分数。名次靠逐场考试查一次排名再定位本人，
     * 场次数量在课程演示规模下很小，不做缓存。
     */
    public List<MyResultItemView> myResults(long studentId) {
        List<MyResultItemView> items = new ArrayList<>();
        for (MyResultRow row : repository.findMyResults(studentId)) {
            List<RankingItemView> rankings = rank(repository.findRankingRows(row.examId()));
            Integer rank = rankings.stream().filter(item -> item.submissionId() == row.submissionId())
                    .map(RankingItemView::rank).findFirst().orElse(null);
            items.add(new MyResultItemView(row.submissionId(), row.examId(), row.examName(), row.submittedAt(),
                    row.objectiveScore(), row.subjectiveScore(), row.totalScore(), row.paperTotalScore(),
                    rank == null ? 0 : rank, rankings.size()));
        }
        return items;
    }

    /**
     * 重算一份答卷的主观题得分与总分，并在全部主观题批完时把状态推进到 {@code GRADED}。
     *
     * <p>总分固定为「客观题得分 + 主观题得分」，不做四舍五入：两部分都已经是一位小数，
     * 相加不会引入新的精度问题。
     */
    private void refreshTotals(long submissionId) {
        SubmissionHeader header = repository.findSubmissionHeader(submissionId).orElseThrow();
        BigDecimal subjective = repository.subjectiveTotal(submissionId);
        BigDecimal total = header.objectiveScore().add(subjective);
        String status = repository.pendingCount(submissionId) == 0 ? "GRADED" : header.status();
        repository.applyGrades(submissionId, subjective, total, status);
    }

    /**
     * 按竞赛排名给排序后的答卷编名次：同分并列且占用名次，形成 {@code 1、2、2、4}。
     *
     * <p>实现要点是「分数变化时名次才跳到当前下标 + 1」，而不是每行递增；
     * 用 {@code compareTo} 比较 BigDecimal，因为 {@code 28} 与 {@code 28.0} 数值相等但 equals 为 false。
     */
    private List<RankingItemView> rank(List<RankingRow> rows) {
        List<RankingItemView> items = new ArrayList<>(rows.size());
        int rank = 0;
        BigDecimal previous = null;
        for (int index = 0; index < rows.size(); index++) {
            RankingRow row = rows.get(index);
            if (previous == null || row.totalScore().compareTo(previous) != 0) {
                rank = index + 1;
                previous = row.totalScore();
            }
            items.add(new RankingItemView(rank, row.submissionId(), row.studentId(), row.studentName(),
                    row.totalScore(), row.objectiveScore(), row.subjectiveScore()));
        }
        return items;
    }

    /** 查某份答卷在本场考试中的名次；答卷未评完时不在排名里，返回 null。 */
    private Integer rankOf(long examId, long submissionId) {
        return rank(repository.findRankingRows(examId)).stream()
                .filter(item -> item.submissionId() == submissionId).map(RankingItemView::rank).findFirst().orElse(null);
    }

    /**
     * 把学生不该看到的字段清空。
     *
     * <p>保留题干、选项和本人作答，清掉标准答案、解析、得分、评语和评分时间——
     * 其中「评分时间」也要清掉：它会暴露教师已经批过这份卷子，虽然分数还没公布。
     */
    private AnswerDetailView mask(AnswerDetailView answer) {
        return new AnswerDetailView(answer.id(), answer.paperQuestionId(), answer.displayOrder(), answer.type(),
                answer.stem(), answer.options(), answer.maxScore(), answer.answerContent(), null, null, null, null,
                null, answer.subjective());
    }

    /** 平均分，保留一位小数；没有有效答卷时返回 null 而不是 0，界面据此显示「—」。 */
    private BigDecimal average(List<BigDecimal> scores) {
        if (scores.isEmpty()) return null;
        return scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(scores.size()), 1, RoundingMode.HALF_UP);
    }

    /** 百分比，保留一位小数；分母为 0 时返回 null。 */
    private BigDecimal ratio(long matched, int total) {
        if (total == 0) return null;
        return BigDecimal.valueOf(matched * 100L).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }

    /** 成绩是否已公布。只有这一个状态代表已公布，集中判断避免各处写字符串比较。 */
    private boolean isPublished(String examStatus) { return "RESULTS_PUBLISHED".equals(examStatus); }

    private ExamHeader requireExam(long examId) {
        return repository.findExamHeader(examId).orElseThrow(() -> notFound("EXAM_NOT_FOUND", "考试不存在"));
    }

    /** 资源归属校验：属主与当前用户不一致时返回 403。 */
    private void requireOwner(long owner, long user, String message) {
        if (owner != user) throw new DomainException(HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN", message);
    }
    private void bad(String code, String message) { throw new DomainException(HttpStatus.BAD_REQUEST, code, message); }
    private DomainException conflict(String code, String message) { return new DomainException(HttpStatus.CONFLICT, code, message); }
    private DomainException notFound(String code, String message) { return new DomainException(HttpStatus.NOT_FOUND, code, message); }
}
