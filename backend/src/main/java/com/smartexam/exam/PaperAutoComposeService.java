package com.smartexam.exam;

import com.smartexam.common.DomainException;
import com.smartexam.common.SettingsCatalog;
import com.smartexam.common.SettingsStore;
import com.smartexam.exam.AutoComposeModels.AutoComposePlan;
import com.smartexam.exam.AutoComposeModels.AutoComposePlanItem;
import com.smartexam.exam.AutoComposeModels.AutoComposeRequest;
import com.smartexam.exam.AutoComposeModels.AutoComposeRule;
import com.smartexam.exam.AutoComposeModels.AutoComposeRuleView;
import com.smartexam.question.KnowledgePointRepository;
import com.smartexam.question.QuestionModels.QuestionView;
import com.smartexam.question.QuestionRepository;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 规则自动组卷：按「题型 / 难度 / 知识点 + 抽几道 + 每道几分」的规则从题库里随机抽题，生成一份方案。
 *
 * <p>本类只读题库、只返回方案，<b>不写任何数据</b>；保存仍由
 * {@link ExamService#createPaper} 完成，理由见 {@link AutoComposeModels}。
 *
 * <p>三条刻意的规则：
 * <ol>
 *   <li><b>不够就报错，不悄悄少给。</b>规则要 5 道而题库只有 3 道时直接返回可读错误，
 *       点明是第几条规则、要几道、实际有几道。试卷是个整体：一份莫名少了 20 分的卷子，
 *       比一句「题目不够」危险得多——教师很可能直接发布出去；</li>
 *   <li><b>同一道题不会被两条规则重复抽中。</b>数据库层面
 *       {@code paper_question} 有 {@code (paper_id, question_id)} 唯一约束，
 *       这里先在内存里排除已抽中的，避免生成一份保存时必然失败的方案；</li>
 *   <li><b>随机在 Java 里做</b>（{@code Collections.shuffle}），不用 {@code ORDER BY RAND()}：
 *       避免依赖数据库方言，也才能同时告诉教师候选池有多大。</li>
 * </ol>
 */
@Service
public class PaperAutoComposeService {
    /**
     * 抽题用的随机源。
     *
     * <p>用 {@link SecureRandom} 而不是共享的 {@code Math.random()}：组卷会被并发调用，
     * 而抽题结果不该因为两位教师同时点了「生成方案」而相互影响。它本身是线程安全的。
     */
    private final Random random = new SecureRandom();

    private final QuestionRepository questions;
    private final KnowledgePointRepository knowledgePoints;
    private final SettingsStore settings;

    public PaperAutoComposeService(QuestionRepository questions, KnowledgePointRepository knowledgePoints,
            SettingsStore settings) {
        this.questions = questions; this.knowledgePoints = knowledgePoints; this.settings = settings;
    }

    /**
     * 按规则生成一份组卷方案。
     *
     * <p>规则按提交顺序依次抽题，前面的规则先抽。顺序有实际影响：把「不限题型」放在最前面，
     * 它可能会把后面「多选题」规则需要的题先抽走，因此界面提示教师先写窄条件。
     *
     * @param request 抽题规则
     * @param teacherId 当前教师，抽题范围限定为其本人题库里启用状态的题目
     */
    public AutoComposePlan compose(AutoComposeRequest request, long teacherId) {
        int requested = request.rules().stream().mapToInt(AutoComposeRule::count).sum();
        int limit = settings.asInt(SettingsCatalog.AUTO_COMPOSE_MAX_QUESTIONS);
        if (requested > limit) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "AUTO_COMPOSE_TOO_MANY",
                    "一次最多抽 " + limit + " 道题，当前规则合计 " + requested + " 道；可拆成两份试卷或调整规则");
        }
        // 已抽中的题目 ID，用 LinkedHashSet 保留抽中顺序，方案里的题序即为规则顺序。
        Set<Long> picked = new LinkedHashSet<>();
        List<AutoComposeRuleView> ruleViews = new ArrayList<>(request.rules().size());
        List<AutoComposePlanItem> items = new ArrayList<>(requested);
        BigDecimal total = BigDecimal.ZERO;

        for (int index = 0; index < request.rules().size(); index++) {
            AutoComposeRule rule = request.rules().get(index);
            int ruleNumber = index + 1;
            String label = label(rule);
            // 知识点先校验存在：填了一个已删除的知识点时，「知识点不存在」比「题目不够」清楚得多。
            if (rule.knowledgePointId() != null && knowledgePoints.findById(rule.knowledgePointId()).isEmpty()) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "KNOWLEDGE_POINT_NOT_FOUND",
                        "第 " + ruleNumber + " 条规则引用的知识点不存在，请重新选择");
            }
            List<Long> pool = new ArrayList<>(questions.findComposeCandidateIds(teacherId, rule.type(),
                    rule.difficulty(), rule.knowledgePointId()));
            pool.removeAll(picked);
            if (pool.size() < rule.count()) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "AUTO_COMPOSE_NOT_ENOUGH",
                        "第 " + ruleNumber + " 条规则（" + label + "）需要 " + rule.count() + " 道题，"
                                + "题库当前只有 " + pool.size() + " 道可用"
                                + (picked.isEmpty() ? "" : "（已排除前面规则抽中的题目）")
                                + "；请减少数量、放宽条件或先补充题目");
            }
            Collections.shuffle(pool, random);
            for (int taken = 0; taken < rule.count(); taken++) {
                long questionId = pool.get(taken);
                picked.add(questionId);
                // 抽中之后才取详情：候选池可能有几百道，没必要为没抽中的题查选项。
                QuestionView question = questions.findById(questionId).orElseThrow(() -> new DomainException(
                        HttpStatus.CONFLICT, "QUESTION_NOT_FOUND", "抽中的题目已被删除，请重新生成方案"));
                items.add(new AutoComposePlanItem(ruleNumber, rule.score(), question));
            }
            BigDecimal subtotal = rule.score().multiply(BigDecimal.valueOf(rule.count()));
            total = total.add(subtotal);
            ruleViews.add(new AutoComposeRuleView(ruleNumber, label, rule.count(), pool.size(),
                    rule.score(), subtotal));
        }
        return new AutoComposePlan(items.size(), total, ruleViews, items);
    }

    /**
     * 拼一条规则的中文描述，如「单选题 / 易 / Java 基础」。
     *
     * <p>三个维度都不限时给出「全部题目」而不是三个「不限」串在一起——
     * 错误提示里出现「不限题型 / 不限难度 / 不限知识点 需要 5 道题」读起来像是坏了。
     */
    private String label(AutoComposeRule rule) {
        List<String> parts = new ArrayList<>(3);
        if (rule.type() != null) parts.add(typeLabel(rule.type()));
        if (rule.difficulty() != null) parts.add(difficultyLabel(rule.difficulty()));
        if (rule.knowledgePointId() != null) {
            parts.add(knowledgePoints.findById(rule.knowledgePointId())
                    .map(KnowledgePointRepository.KnowledgePoint::name).orElse("未知知识点"));
        }
        return parts.isEmpty() ? "全部题目" : String.join(" / ", parts);
    }

    /** 题型中文名。与前端 {@code typeLabels} 一致，错误信息里出现英文枚举名对教师没有意义。 */
    private String typeLabel(com.smartexam.question.QuestionModels.Type type) {
        return switch (type) {
            case SINGLE_CHOICE -> "单选题";
            case MULTIPLE_CHOICE -> "多选题";
            case TRUE_FALSE -> "判断题";
            case SHORT_ANSWER -> "简答题";
            case PROGRAMMING -> "编程题";
        };
    }

    /** 难度中文名。 */
    private String difficultyLabel(com.smartexam.question.QuestionModels.Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> "易";
            case MEDIUM -> "中";
            case HARD -> "难";
        };
    }
}
