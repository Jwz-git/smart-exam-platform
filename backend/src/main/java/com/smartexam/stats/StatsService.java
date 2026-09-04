package com.smartexam.stats;

import com.smartexam.exam.GradingModels;
import com.smartexam.exam.GradingModels.ExamResultsView;
import com.smartexam.exam.GradingModels.RankingItemView;
import com.smartexam.exam.GradingService;
import com.smartexam.stats.StatsModels.ActivityOverview;
import com.smartexam.stats.StatsModels.BankOverview;
import com.smartexam.stats.StatsModels.ExamAnalysisView;
import com.smartexam.stats.StatsModels.GroupCount;
import com.smartexam.stats.StatsModels.OverviewView;
import com.smartexam.stats.StatsModels.QuestionStat;
import com.smartexam.stats.StatsModels.ScoreBucket;
import com.smartexam.stats.StatsRepository.AnswerRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 统计分析业务逻辑：题库分布、教学活动概况、单场考试的成绩分布与逐题正确率。
 *
 * <p>本类只读不写，因此全部方法标注只读事务。
 *
 * <p>一个关键的复用决定：单场考试的平均分、最高分、最低分、及格率和排名<b>不在这里重算</b>，
 * 而是直接调用 {@link GradingService#results}。好处有两个——「谁能看这场考试」的归属校验只有一处实现，
 * 成绩管理页和统计分析页显示的数字也不可能对不上。本类只负责它独有的两件事：
 * 把已评完的总分分桶成分布，以及把逐题作答聚合成正确率。
 */
@Service
@Transactional(readOnly = true)
public class StatsService {
    /** 题型中文名。与前端的 {@code questionTypeLabels} 一致，接口直接下发标签，前端不必再维护一份映射。 */
    private static final Map<String, String> TYPE_LABELS = Map.of(
            "SINGLE_CHOICE", "单选题", "MULTIPLE_CHOICE", "多选题", "TRUE_FALSE", "判断题",
            "SHORT_ANSWER", "简答题", "PROGRAMMING", "编程题");
    /** 难度中文名。 */
    private static final Map<String, String> DIFFICULTY_LABELS = Map.of("EASY", "简单", "MEDIUM", "中等", "HARD", "困难");

    /**
     * 题型与难度的展示顺序。
     *
     * <p>必须单独写成数组，不能拿上面两张 {@code Map.of} 的键集当顺序用：{@code Map.of} 返回的不可变映射
     * 不保证迭代顺序（实测会给出 {@code 判断题、编程题、单选题…} 这样的乱序），而题型顺序是业务顺序——
     * 单选、多选、判断、简答、编程，难度是易到难。这个坑是在真实数据上看出来的，H2 测试按 key 断言时看不出来。
     */
    private static final String[] TYPE_ORDER =
            { "SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE", "SHORT_ANSWER", "PROGRAMMING" };
    private static final String[] DIFFICULTY_ORDER = { "EASY", "MEDIUM", "HARD" };

    /**
     * 成绩分布的分段边界，按「占试卷满分的百分比」左闭右开，最后一段取到 100 含。
     *
     * <p>第一段直接取 0—59% 而不是继续细分：及格线本身就是 60%，把不及格的人再分成几段
     * 对教师没有额外信息量，反而让图变碎。
     */
    private static final int[][] BUCKETS = { { 0, 60 }, { 60, 70 }, { 70, 80 }, { 80, 90 }, { 90, 101 } };
    /** 与 BUCKETS 一一对应的标签。 */
    private static final String[] BUCKET_LABELS = { "0—59%（不及格）", "60—69%", "70—79%", "80—89%", "90—100%" };
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final StatsRepository repository;
    private final GradingService grading;

    public StatsService(StatsRepository repository, GradingService grading) {
        this.repository = repository;
        this.grading = grading;
    }

    /**
     * 教师工作台的整体概况。
     *
     * <p>八个计数分成四次查询而不是八次：题目、试卷、考试、答卷各一次，同类计数用 CASE WHEN 在一句里算完。
     */
    public OverviewView overview(long teacherId) {
        long[] questions = repository.questionStatusCounts(teacherId);
        List<GroupCount> byType = repository.countByColumn(teacherId, "type", TYPE_LABELS);
        List<GroupCount> byDifficulty = repository.countByColumn(teacherId, "difficulty", DIFFICULTY_LABELS);
        BankOverview bank = new BankOverview(questions[0], questions[1], questions[2],
                repository.knowledgePointCount(), sortByLabels(byType, TYPE_ORDER),
                sortByLabels(byDifficulty, DIFFICULTY_ORDER), repository.countByKnowledgePoint(teacherId));

        long[] papers = repository.paperCounts(teacherId);
        long[] exams = repository.examCounts(teacherId);
        long[] submissions = repository.submissionCounts(teacherId);
        ActivityOverview activity = new ActivityOverview(papers[0], papers[1], exams[0], exams[1], exams[2],
                submissions[0], submissions[1], repository.pendingSubjectiveCount(teacherId));
        return new OverviewView(bank, activity);
    }

    /**
     * 单场考试的分析。
     *
     * <p>第一句就调 {@link GradingService#results}，归属不对时它会抛 403、考试不存在时抛 404，
     * 因此本方法不需要自己校验权限——重复实现校验才是风险。
     */
    public ExamAnalysisView examAnalysis(long examId, long teacherId) {
        ExamResultsView results = grading.results(examId, teacherId);
        BigDecimal total = results.paperTotalScore();
        BigDecimal passScore = total.multiply(grading.passRatio()).setScale(1, RoundingMode.HALF_UP);
        return new ExamAnalysisView(results.examId(), results.examName(), results.examStatus(),
                results.resultsPublished(), total, passScore, repository.submissionCount(examId),
                results.gradedCount(), results.averageScore(), results.highestScore(), results.lowestScore(),
                results.passRate(), distribution(results.rankings(), total),
                questionStats(repository.findAnswerRows(examId)));
    }

    /**
     * 把已评完答卷的总分分桶。
     *
     * <p>只有已评完的答卷会出现在 {@code rankings} 里，所以分布图与平均分的口径天然一致：
     * 两者都不含未批完的卷子。试卷满分为 0 时不做除法，直接返回空分布而不是抛异常。
     */
    private List<ScoreBucket> distribution(List<RankingItemView> rankings, BigDecimal paperTotalScore) {
        List<ScoreBucket> buckets = new ArrayList<>(BUCKETS.length);
        if (paperTotalScore == null || paperTotalScore.compareTo(BigDecimal.ZERO) <= 0) return buckets;
        int[] counts = new int[BUCKETS.length];
        for (RankingItemView item : rankings) {
            BigDecimal percent = item.totalScore().multiply(HUNDRED)
                    .divide(paperTotalScore, 4, RoundingMode.HALF_UP);
            for (int index = 0; index < BUCKETS.length; index++) {
                if (percent.doubleValue() >= BUCKETS[index][0] && percent.doubleValue() < BUCKETS[index][1]) {
                    counts[index]++;
                    break;
                }
            }
        }
        for (int index = 0; index < BUCKETS.length; index++) {
            buckets.add(new ScoreBucket(BUCKET_LABELS[index], counts[index], percent(counts[index], rankings.size())));
        }
        return buckets;
    }

    /**
     * 把逐题作答明细聚合成每题一行的统计。
     *
     * <p>两处口径差异必须区分开，否则数字会误导教师：
     * <ul>
     *   <li><b>客观题</b>全部已交卷答卷都算：系统在交卷事务里已经判完分，没有「未评分」这个状态。
     *       满分即答对（本项目多选不给部分分），因此正确率 = 满分人数 / 答卷数。</li>
     *   <li><b>主观题</b>只统计已评分的答卷：未评分时分数是默认的 0，计入会让平均分随阅卷进度漂移。
     *       主观题没有「对错」，正确率为 {@code null}，改用得分率衡量。</li>
     * </ul>
     */
    private List<QuestionStat> questionStats(List<AnswerRow> rows) {
        Map<Long, List<AnswerRow>> grouped = new LinkedHashMap<>();
        for (AnswerRow row : rows) grouped.computeIfAbsent(row.paperQuestionId(), key -> new ArrayList<>()).add(row);

        List<QuestionStat> stats = new ArrayList<>(grouped.size());
        grouped.forEach((paperQuestionId, group) -> {
            AnswerRow first = group.get(0);
            boolean subjective = GradingModels.SUBJECTIVE_TYPES.contains(first.type());
            int totalCount = 0, blank = 0, fullMark = 0, ungraded = 0, scored = 0;
            BigDecimal sum = BigDecimal.ZERO;
            for (AnswerRow row : group) {
                // submissionId 为 0 说明这场考试还没有已交卷的答卷，这一行只是占位，不能计入任何计数。
                if (row.submissionId() == 0) continue;
                totalCount++;
                if (isBlank(row.answerContent())) blank++;
                BigDecimal score = row.score() == null ? BigDecimal.ZERO : row.score();
                if (subjective && !row.graded()) { ungraded++; continue; }
                scored++;
                sum = sum.add(score);
                if (score.compareTo(first.maxScore()) >= 0) fullMark++;
            }
            BigDecimal average = scored == 0 ? null : sum.divide(BigDecimal.valueOf(scored), 1, RoundingMode.HALF_UP);
            BigDecimal scoreRate = average == null || first.maxScore().compareTo(BigDecimal.ZERO) <= 0 ? null
                    : average.multiply(HUNDRED).divide(first.maxScore(), 1, RoundingMode.HALF_UP);
            stats.add(new QuestionStat(paperQuestionId, first.displayOrder(), first.type(), first.stem(),
                    first.maxScore(), subjective, totalCount, totalCount - blank, blank,
                    subjective ? null : fullMark, subjective ? null : percent(fullMark, totalCount),
                    average, scoreRate, ungraded));
        });
        return stats;
    }

    /**
     * 判断一段作答内容是否等于「没写」。
     *
     * <p>四种形态都要算留空：SQL NULL、JSON 的 {@code null} 字面量、空字符串 {@code ""}、空数组 {@code []}。
     * 前两种来自不同的写入路径（保存答案时写 SQL NULL，自动判分补行时写字符串 {@code 'null'}），
     * 后两种来自学生清空了输入框或取消了全部选项。
     */
    private boolean isBlank(String answerContent) {
        if (answerContent == null) return true;
        String text = answerContent.trim();
        return text.isEmpty() || "null".equals(text) || "\"\"".equals(text) || "[]".equals(text);
    }

    /** 求百分比，保留一位小数；分母为 0 时返回 0.0 而不是 null，因为「没人答」的正确率就是 0。 */
    private BigDecimal percent(int part, int whole) {
        if (whole <= 0) return BigDecimal.ZERO.setScale(1);
        return BigDecimal.valueOf(part).multiply(HUNDRED).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    /**
     * 按给定顺序重排分组结果，并补上计数为 0 的分组。
     *
     * <p>数据库 GROUP BY 只会返回出现过的值，直接展示会让「一道编程题都没有」变成图上没有这一项。
     * 显式补 0 才能让教师看出题型覆盖的缺口，顺序也固定为业务顺序而不是数据库返回顺序。
     */
    private List<GroupCount> sortByLabels(List<GroupCount> source, String... keys) {
        Map<String, GroupCount> found = new LinkedHashMap<>();
        source.forEach(item -> found.put(item.key(), item));
        List<GroupCount> ordered = new ArrayList<>(keys.length);
        for (String key : keys) {
            GroupCount item = found.get(key);
            ordered.add(item != null ? item : new GroupCount(key, label(key), 0));
        }
        return ordered;
    }

    /** 取分组值的中文名；两张标签表都没有时退回原值，避免出现空白标签。 */
    private String label(String key) {
        return TYPE_LABELS.getOrDefault(key, DIFFICULTY_LABELS.getOrDefault(key, key));
    }
}
