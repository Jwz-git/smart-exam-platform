package com.smartexam.system;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 系统设置页的响应模型。
 *
 * <p>本页是<b>只读</b>的运行时信息面板，不是参数编辑器。这样设计有两个理由：
 * <ul>
 *   <li>本项目的可配置项（数据库、JWT、AI 服务商、自动交卷间隔）全部来自环境变量，
 *       在界面上改这些值只会造成「界面显示的和进程实际使用的不一致」；</li>
 *   <li>把真实生效的配置显式展示出来，恰好能回答演示现场最常问的几个问题：
 *       时区是不是 UTC、令牌多久过期、及格线怎么定的、AI 到底配没配。</li>
 * </ul>
 *
 * <p>安全约束：本响应<b>绝不包含</b>任何密钥、密码或数据库连接串。AI 密钥只以
 * {@code configured} 这个布尔值体现「配了还是没配」，密钥本身既不出现在响应里也不写进日志。
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
     * @param autoSubmitIntervalMs 服务端扫描超时答卷的间隔（毫秒），与浏览器是否打开无关
     * @param passRatioPercent     及格线占试卷总分的百分比
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

    /** 系统设置总响应。 */
    public record SettingsView(RuntimeInfo runtime, SecurityInfo security, ExamRuleInfo exam,
            AiInfo ai, DatabaseInfo database) {}
}
