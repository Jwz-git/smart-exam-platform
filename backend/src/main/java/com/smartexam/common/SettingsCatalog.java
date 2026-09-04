package com.smartexam.common;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 可在界面上修改的设置项白名单。
 *
 * <p>本类是「哪些参数允许被改」的唯一定义，也是这个功能的安全边界：{@link SettingsStore}
 * 只认这里列出的键，未知键一律拒绝，因此不存在「往 system_setting 里插一行就能改任意配置」的通道。
 *
 * <p>没有进入白名单的配置全部只来自环境变量，理由分三类，界面上也如实说明：
 * <ul>
 *   <li><b>改了会立刻让系统不可用：</b>数据库连接、JWT 签名密钥；</li>
 *   <li><b>绝不能出现在浏览器里：</b>AI 密钥——它只在后端进程内使用，任何接口都不下发；
 *       AI 地址与协议同样不开放，否则「后端向哪个地址发请求」就变成了一个界面输入框；</li>
 *   <li><b>改了不会立刻生效：</b>自动交卷扫描间隔（{@code @Scheduled} 的触发器在启动时注册）、
 *       AI 读超时（超时写进了启动时构建的 HTTP 客户端）。这两项如果做成可编辑，
 *       就恰好制造了「界面改完但进程没变」的显示与生效不一致——这正是本页原先只读的理由。</li>
 * </ul>
 *
 * <p>换句话说：可编辑的只有「改了立刻生效、且改错也不会让系统整体不可用」的那几项。
 */
public final class SettingsCatalog {
    private SettingsCatalog() {}

    /** 设置项的值类型，决定校验方式与前端控件形态。 */
    public enum ValueType { INTEGER, DECIMAL, BOOLEAN }

    /** 及格线占试卷总分的百分比。及格率、及格分数线两处统计都读它。 */
    public static final String PASS_RATIO_PERCENT = "exam.pass-ratio-percent";
    /** 访问令牌有效期（分钟）。改动只影响此后新签发的令牌，已签发的仍按原有效期。 */
    public static final String ACCESS_TOKEN_MINUTES = "security.access-token-minutes";
    /** 单次批量导入的最大行数。 */
    public static final String IMPORT_MAX_ROWS = "question.import-max-rows";
    /** 批量导入遇到重复题干时是否跳过。关掉后重复题干会照常写入。 */
    public static final String IMPORT_SKIP_DUPLICATE = "question.import-skip-duplicate-stem";
    /** 自动组卷单次最多抽取的题目数。 */
    public static final String AUTO_COMPOSE_MAX_QUESTIONS = "paper.auto-compose-max-questions";
    /** 错题重练默认每组题数。 */
    public static final String PRACTICE_BATCH_SIZE = "practice.batch-size";

    /**
     * 一个可编辑设置项的定义。
     *
     * @param key         设置键，同时是数据库主键和接口字段名
     * @param label       界面显示的中文名
     * @param group       分组，与系统设置页的卡片一一对应
     * @param type        值类型
     * @param min         允许的最小值；{@code BOOLEAN} 为 {@code null}
     * @param max         允许的最大值；{@code BOOLEAN} 为 {@code null}
     * @param unit        单位后缀，用于界面展示；无单位时为空串
     * @param description 这一项改了会影响什么，直接展示给管理员
     */
    public record Definition(String key, String label, String group, ValueType type,
            BigDecimal min, BigDecimal max, String unit, String description) {}

    /** 白名单全集，顺序即界面展示顺序。 */
    public static final List<Definition> DEFINITIONS = List.of(
            new Definition(PASS_RATIO_PERCENT, "及格线", "考试与评分", ValueType.DECIMAL,
                    new BigDecimal("0"), new BigDecimal("100"), "%",
                    "及格分数线 = 试卷总分 × 该百分比。改动后成绩页与统计分析页的及格率立刻按新线重算，"
                            + "历史分数本身不变。"),
            new Definition(ACCESS_TOKEN_MINUTES, "令牌有效期", "认证与安全", ValueType.INTEGER,
                    new BigDecimal("5"), new BigDecimal("1440"), "分钟",
                    "只影响此后新签发的令牌；已经发出去的令牌仍按签发时的有效期过期，无状态 JWT 无法追回。"),
            new Definition(IMPORT_MAX_ROWS, "单次导入行数上限", "题库", ValueType.INTEGER,
                    new BigDecimal("10"), new BigDecimal("2000"), "行",
                    "超过上限时整批拒绝并提示分批导入。上限存在的意义是避免一次请求处理过久，"
                            + "调高后请留意导入接口的响应时间。"),
            new Definition(IMPORT_SKIP_DUPLICATE, "导入跳过重复题干", "题库", ValueType.BOOLEAN,
                    null, null, "",
                    "开启时题干重复的行只跳过、不写库（默认）。关闭后重复题干会照常导入——"
                            + "同一题干配不同选项是合法出题手法，需要时才关。"),
            new Definition(AUTO_COMPOSE_MAX_QUESTIONS, "自动组卷抽题上限", "试卷", ValueType.INTEGER,
                    new BigDecimal("1"), new BigDecimal("100"), "道",
                    "一次自动组卷最多抽多少道题。超过上限时直接报错，避免误填规则抽出一份几百题的试卷。"),
            new Definition(PRACTICE_BATCH_SIZE, "错题重练每组题数", "错题本", ValueType.INTEGER,
                    new BigDecimal("1"), new BigDecimal("50"), "道",
                    "学生点「开始重练」时默认取多少道错题。学生可以要求更少，但不能超过这个上限。"));

    /** 按键查定义；未在白名单里时返回空，由调用方转成 400。 */
    public static Optional<Definition> find(String key) {
        return DEFINITIONS.stream().filter(definition -> definition.key().equals(key)).findFirst();
    }
}
