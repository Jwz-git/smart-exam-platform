package com.smartexam.practice;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartexam.common.DomainException;
import com.smartexam.common.SettingsCatalog;
import com.smartexam.common.SettingsStore;
import com.smartexam.exam.GradingModels;
import com.smartexam.exam.ObjectiveGrader;
import com.smartexam.practice.PracticeModels.PracticeAnswerRequest;
import com.smartexam.practice.PracticeModels.PracticeAnswerResultView;
import com.smartexam.practice.PracticeModels.PracticeQuestionView;
import com.smartexam.practice.PracticeModels.PracticeResultView;
import com.smartexam.practice.PracticeModels.PracticeSetView;
import com.smartexam.practice.PracticeModels.PracticeSubmitRequest;
import com.smartexam.practice.PracticeModels.WrongBookView;
import com.smartexam.practice.PracticeModels.WrongQuestionView;
import com.smartexam.practice.PracticeRepository.GradingContext;
import com.smartexam.practice.PracticeRepository.PracticeRow;
import com.smartexam.practice.PracticeRepository.WrongRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 错题本与错题重练的业务规则。
 *
 * <p>四条硬约束：
 * <ol>
 *   <li><b>只收录已公布成绩的考试。</b>范围定义在 {@link PracticeRepository}，
 *       成绩公布前一个字都不会出现在错题本里；</li>
 *   <li><b>练习集不下发答案。</b>标准答案与解析只在提交之后随判分结果返回，
 *       否则重练就变成抄一遍答案；</li>
 *   <li><b>判分复用交卷时的同一套规则</b>（{@link ObjectiveGrader}）。
 *       「考试判错、重练判对」这种分歧对学生毫无解释空间；</li>
 *   <li><b>重练不改动任何成绩。</b>练习记录写在独立的 {@code practice_attempt} 表，
 *       {@code submission} 与 {@code submission_answer} 一个字段都不碰。</li>
 * </ol>
 */
@Service
public class PracticeService {
    private final PracticeRepository repository;
    private final ObjectiveGrader grader;
    private final SettingsStore settings;

    public PracticeService(PracticeRepository repository, ObjectiveGrader grader, SettingsStore settings) {
        this.repository = repository; this.grader = grader; this.settings = settings;
    }

    /**
     * 错题本。
     *
     * <p>顺带算好四个计数（总数、可重练的客观题、只能复习的主观题、已掌握），在内存里数一遍
     * 而不是再发四条 COUNT 查询：错题本的规模是「一个学生做过的考试里错的题」，
     * 这个量级下多一次遍历远比多四次往返便宜。
     */
    public WrongBookView wrongBook(long studentId) {
        List<WrongRow> rows = repository.findWrongQuestions(studentId);
        List<WrongQuestionView> items = new ArrayList<>(rows.size());
        int objective = 0;
        int subjective = 0;
        int mastered = 0;
        for (WrongRow row : rows) {
            boolean isSubjective = GradingModels.SUBJECTIVE_TYPES.contains(row.type());
            boolean isMastered = Boolean.TRUE.equals(row.lastCorrect());
            if (isSubjective) subjective++; else objective++;
            if (isMastered) mastered++;
            items.add(new WrongQuestionView(row.answerId(), row.submissionId(), row.paperQuestionId(),
                    row.examId(), row.examName(), row.displayOrder(), row.type(), row.stem(), row.options(),
                    row.maxScore(), row.score(), row.myAnswer(), row.standardAnswer(), row.explanation(),
                    row.gradingComment(), row.submittedAt(), isSubjective, blank(row.myAnswer()),
                    row.practiceCount(), row.lastCorrect(), row.lastPracticedAt(), isMastered));
        }
        return new WrongBookView(items.size(), objective, subjective, mastered,
                settings.asInt(SettingsCatalog.PRACTICE_BATCH_SIZE), items);
    }

    /**
     * 取一组练习题。
     *
     * @param size           想练几道；不传按系统设置的默认值，且不允许超过它。
     *                       上限来自设置而不是写死，是为了让管理员能按班级情况调整
     * @param onlyUnmastered 是否只练「还没练对」的题，默认 true——已经练对的题再练意义不大
     */
    public PracticeSetView practiceSet(long studentId, Integer size, boolean onlyUnmastered) {
        int limit = settings.asInt(SettingsCatalog.PRACTICE_BATCH_SIZE);
        int requested = size == null ? limit : Math.max(1, Math.min(size, limit));
        List<PracticeRow> rows = repository.findPracticeCandidates(studentId, onlyUnmastered, requested);
        if (rows.isEmpty()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "PRACTICE_NO_QUESTION", onlyUnmastered
                    ? "没有需要重练的客观题了——错题本里的客观题都已经练对，或者还没有已公布成绩的错题"
                    : "错题本里还没有可重练的客观题；主观题需要对照参考答案自行复习");
        }
        List<PracticeQuestionView> questions = rows.stream()
                .map(row -> new PracticeQuestionView(row.paperQuestionId(), row.type(), row.stem(),
                        row.options(), row.maxScore(), row.examName(), row.practiceCount()))
                .toList();
        return new PracticeSetView(questions.size(), questions);
    }

    /**
     * 提交一组练习作答并即时判分。
     *
     * <p>每道题都要先通过 {@link PracticeRepository#findGradingContext} 确认「确实是本人已公布考试里的错题」，
     * 查不到就返回 404。这一步不能省：本方法的响应<b>包含标准答案和解析</b>，
     * 少了这道校验，任何学生都能拿一个任意的题目 ID 换到答案。
     *
     * <p>整个方法在一个事务里：一次练习的记录要么全部写下，要么一条都不写，
     * 不会出现「前三题记下了、第四题因为 ID 非法整批失败」时留下半截练习记录的情况。
     */
    @Transactional
    public PracticeResultView submit(long studentId, PracticeSubmitRequest request) {
        Set<Long> seen = new HashSet<>();
        List<PracticeAnswerResultView> results = new ArrayList<>(request.answers().size());
        Instant now = Instant.now();
        int correctCount = 0;
        for (PracticeAnswerRequest answer : request.answers()) {
            if (!seen.add(answer.paperQuestionId())) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "DUPLICATE_ANSWER", "同一道题不能重复提交");
            }
            GradingContext context = repository.findGradingContext(studentId, answer.paperQuestionId())
                    .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "WRONG_QUESTION_NOT_FOUND",
                            "这道题不在你的错题本里，无法重练"));
            if (!grader.isObjective(context.type())) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "PRACTICE_NOT_OBJECTIVE",
                        "简答题和编程题没有确定性判分规则，只能对照参考答案自行复习");
            }
            boolean correct = grader.matches(context.standardAnswer(), answer.answerContent());
            if (correct) correctCount++;
            repository.recordAttempt(studentId, answer.paperQuestionId(), answer.answerContent(), correct, now);
            results.add(new PracticeAnswerResultView(answer.paperQuestionId(), context.stem(), correct,
                    answer.answerContent(), context.standardAnswer(), context.explanation()));
        }
        return new PracticeResultView(results.size(), correctCount, ratio(correctCount, results.size()), results);
    }

    /** 是否未作答。JSON null、空数组和空白字符串都算未作答，与答题页的判断保持一致。 */
    private boolean blank(JsonNode answer) {
        if (answer == null || answer.isNull()) return true;
        if (answer.isArray()) return answer.isEmpty();
        if (answer.isTextual()) return answer.asText().isBlank();
        return false;
    }

    /** 正确率百分比，保留一位小数；一道题都没有时返回 null。 */
    private BigDecimal ratio(int matched, int total) {
        if (total == 0) return null;
        return BigDecimal.valueOf(matched * 100L).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }
}
