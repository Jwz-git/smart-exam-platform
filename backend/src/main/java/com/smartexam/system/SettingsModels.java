package com.smartexam.system;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 系统设置页的响应模型。
 *
 * <p>页面分成两半，界限很清楚：
 * <ul>
 *   <li><b>可编辑</b>（{@link SettingsView#editable}）：白名单里的若干运行参数，
 *       由管理员在界面上修改，覆盖值存在 {@code system_setting} 表，保存后立刻生效。
 *       白名单与「为什么只有这几项」见 {@link com.smartexam.common.SettingsCatalog}；</li>
 *   <li><b>只读</b>（其余五组）：进程当前真正生效的运行信息与启动时固化的参数。
 *       把它们显式展示出来，恰好能回答演示现场最常问的几个问题：
 *       时区是不是 UTC、令牌多久过期、及格线怎么定的、迁移到第几版、AI 到底配没配。</li>
 * </ul>
 *
 * <p>可编辑项一律给出「当前值 + 默认值 + 是否被覆盖」三件信息，因此不会出现
 * 「界面显示的和进程实际使用的不一致」——这正是本页原先做成纯只读时担心的问题。
 *
 * <p>安全约束：本响应<b>绝不包含</b>任何密钥、密码或数据库连接串。AI 密钥只以
 * {@code configured} 这个布尔值体现「配了还是没配」，密钥本身既不出现在响应里也不写进日志；
 * 密钥、连接串、AI 地址与协议也都不在可编辑白名单里。
 */
public final class SettingsModels {
    private SettingsModels() {}

    /**
     * 运行环境信息。
     *
     * @param serverTimeZone JVM 默认时区。它与数据库会话时区（强制 UTC）不同是正常的，
     *                       因为所有时间都以 {@code Instant} 传输，展示时才转本地时区
     * @param serverTime     取值时刻的服务端时间，可用来现场核对前端倒计时的基准
     */
    public record RuntimeInfo(String service, String springBootVersion, String javaVersion,
            String serverTimeZone, Instant serverTime) {}

    /**
     * 安全相关的生效参数。
     *
     * @param accessTokenMinutes 访问令牌有效期（分钟）
     * @param tokenRevocable     令牌是否可吊销。本项目为 {@code false}，靠每次请求回查用户状态拦停用账号，
     *                           这是有意的取舍，写进响应而不是藏起来
     */
    public record SecurityInfo(String tokenType, long accessTokenMinutes, String passwordAlgorithm,
            boolean tokenRevocable) {}

    /**
     * 考试与评分的生效规则。
     *
     * @param autoSubmitIntervalMs 服务端扫描超时答卷的间隔（毫秒），与浏览器是否打开无关。
     *                             这一项只能改环境变量：{@code @Scheduled} 的触发器在启动时注册，
     *                             界面上改了不会重建触发器，因此不放进可编辑白名单
     * @param passRatioPercent     及格线占试卷总分的百分比，可在界面上修改
     */
    public record ExamRuleInfo(long autoSubmitIntervalMs, BigDecimal passRatioPercent,
            String rankingRule, String partialCreditRule) {}

    /**
     * AI 配置状态。
     *
     * <p>只回传协议、地址、模型和几个数值参数，用于判断「是不是配错了服务商」；
     * {@code configured} 为 {@code false} 时前端提示未配置，AI 出题按钮仍可点，
     * 点击后由后端返回可读错误——这条降级路径本身也是演示内容。
     */
    public record AiInfo(boolean configured, String protocol, String baseUrl, String model,
            int timeoutSeconds, int maxTokens) {}

    /**
     * 数据库信息。
     *
     * @param schemaVersion 已应用的最高 Flyway 版本号；读不到迁移历史表时为 {@code null}
     *                      （例如自动化测试用的 H2 是手写 schema，不执行 Flyway）
     */
    public record DatabaseInfo(String product, String version, String schemaVersion, String sessionTimeZone) {}

    /**
     * 一个可编辑设置项的完整状态。
     *
     * @param key          设置键，保存时按这个键回传
     * @param type         值类型：{@code INTEGER}、{@code DECIMAL} 或 {@code BOOLEAN}，前端据此选控件
     * @param value        当前生效值
     * @param defaultValue 环境变量给出的默认值，也是「恢复默认」之后的值
     * @param overridden   当前值是否来自数据库覆盖；{@code false} 表示仍是默认值
     * @param min          允许的最小值，布尔项为 {@code null}
     * @param max          允许的最大值，布尔项为 {@code null}
     * @param updatedAt    最后一次修改时间，未被改过时为 {@code null}
     * @param updatedBy    最后一次修改人显示名，未被改过时为 {@code null}
     */
    public record SettingItemView(String key, String label, String group, String type, String value,
            String defaultValue, boolean overridden, BigDecimal min, BigDecimal max, String unit,
            String description, Instant updatedAt, String updatedBy) {}

    /**
     * 修改设置的请求。
     *
     * <p>{@code values} 里的值为 {@code null} 或空串表示「恢复默认」——也就是删掉覆盖行，
     * 而不是写回一个当时的默认值。只传要改的键即可，没传的键保持不变。
     */
    public record UpdateSettingsRequest(
            @jakarta.validation.constraints.NotEmpty(message = "请至少提交一项设置")
            @jakarta.validation.constraints.Size(max = 50, message = "一次最多提交 50 项设置")
            java.util.Map<String, String> values) {}

    /**
     * 修改结果。
     *
     * @param changed  实际发生变化的项数。提交了但值没变的项不计入，界面因此能如实说「已更新 0 项」
     * @param settings 修改后的完整设置视图，前端一次请求就能刷新整页
     */
    public record SettingsUpdateResult(int changed, SettingsView settings) {}

    /** 系统设置总响应。{@code editable} 是可修改项，其余五组只读。 */
    public record SettingsView(List<SettingItemView> editable, RuntimeInfo runtime, SecurityInfo security,
            ExamRuleInfo exam, AiInfo ai, DatabaseInfo database) {}
}
