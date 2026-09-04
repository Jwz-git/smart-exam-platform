package com.smartexam.exam;

import com.smartexam.question.QuestionModels.Difficulty;
import com.smartexam.question.QuestionModels.QuestionView;
import com.smartexam.question.QuestionModels.Type;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * 规则自动组卷的请求与响应模型。
 *
 * <p>本接口<b>只生成方案，不写库</b>：它返回一组抽中的题目和每题分值，教师在组卷页里
 * 确认、微调（换题、改分、调序）之后，仍然走原来的 {@code POST /papers} 保存。
 *
 * <p>这样设计有三个理由：
 * <ol>
 *   <li><b>分值合计等于总分、题目归属与启用状态</b>这些校验只在 {@code ExamService#createPaper}
 *       里实现一次。自动组卷若自己写库，就多了一条能绕过这些校验的路径——
 *       题库的三条入口（手工、AI 草稿、批量导入）共用一套校验，组卷也应当只有一条写入路径；</li>
 *   <li><b>抽题是随机的。</b>如果「预览」和「保存」各抽一次，两次结果必然不同，
 *       教师看到的方案就不是最终保存的那份。返回具体题目再由前端提交，才能保证眼见即所得；</li>
 *   <li>教师本来就需要微调。规则抽出来的卷子很少能直接用，多半要换掉一两道题。</li>
 * </ol>
 */
public final class AutoComposeModels {
    private AutoComposeModels() {}

    /**
     * 一条抽题规则。
     *
     * <p>三个筛选维度都允许为 {@code null}，表示「不限」；这样「随便抽 5 道题，每题 10 分」
     * 也是一条合法规则，不必强迫教师把三个维度都填满。
     *
     * @param type             题型；{@code null} 表示不限题型
     * @param difficulty       难度；{@code null} 表示不限难度
     * @param knowledgePointId 知识点；{@code null} 表示不限知识点
     * @param count            抽题数量，1—100
     * @param score            该规则抽中的每道题在本试卷中的分值，最多一位小数
     */
    public record AutoComposeRule(Type type, Difficulty difficulty, Long knowledgePointId,
            @NotNull @Positive @Max(value = 100, message = "单条规则最多抽 100 道题") Integer count,
            @NotNull @DecimalMin("0.1") @Digits(integer = 5, fraction = 1) BigDecimal score) {}

    /**
     * 自动组卷请求。
     *
     * <p>规则按顺序生效，前面的规则先抽：同一道题不会被两条规则重复抽中，
     * 因此「先抽窄条件、再抽宽条件」能得到更符合预期的结果，这一点在界面上也写明了。
     */
    public record AutoComposeRequest(
            @NotEmpty(message = "请至少添加一条抽题规则")
            @Size(max = 10, message = "一次最多 10 条规则")
            @Valid List<AutoComposeRule> rules) {}

    /**
     * 抽中的一道题。
     *
     * <p>直接内嵌完整的 {@link QuestionView}，前端因此可以把方案原样填进组卷页的「试卷内容」，
     * 不需要再按 ID 逐个回查题目。范围仍是教师本人的题库，与题库列表接口下发的内容一致。
     *
     * @param ruleIndex 来自第几条规则（从 1 开始），界面据此显示「按规则 2 抽中」
     */
    public record AutoComposePlanItem(int ruleIndex, BigDecimal score, QuestionView question) {}

    /**
     * 单条规则的执行结果。
     *
     * @param label    规则的中文描述，如「单选题 / 易 / Java 基础」，直接展示给教师
     * @param poolSize 满足该规则且未被前面规则抽走的候选题数量。它比「抽了几道」更有信息量：
     *                 池子刚好等于抽题数时，这条规则其实没有任何随机空间，换一次还是同一批题
     * @param subtotal 该规则贡献的分数合计（{@code count × score}）
     */
    public record AutoComposeRuleView(int ruleIndex, String label, int count, int poolSize,
            BigDecimal score, BigDecimal subtotal) {}

    /**
     * 自动组卷方案。
     *
     * @param questionCount 抽中的题目总数
     * @param totalScore    方案总分，等于各规则小计之和；保存试卷时它必须与逐题分值合计一致
     */
    public record AutoComposePlan(int questionCount, BigDecimal totalScore,
            List<AutoComposeRuleView> rules, List<AutoComposePlanItem> items) {}
}
