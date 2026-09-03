package com.smartexam.system;

import com.smartexam.ai.AiProperties;
import com.smartexam.common.ApiResponse;
import com.smartexam.exam.GradingService;
import com.smartexam.system.SettingsModels.AiInfo;
import com.smartexam.system.SettingsModels.DatabaseInfo;
import com.smartexam.system.SettingsModels.ExamRuleInfo;
import com.smartexam.system.SettingsModels.RuntimeInfo;
import com.smartexam.system.SettingsModels.SecurityInfo;
import com.smartexam.system.SettingsModels.SettingsView;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootVersion;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统设置接口：返回当前进程真正生效的运行参数，供教师和管理员核对环境。
 *
 * <p>与 {@code /api/health} 的区别是「谁能看」：健康检查匿名可访问，因此只回状态和时间；
 * 本接口需要登录且限定教师或管理员，才敢返回版本号、数据库产品和 AI 服务商这类环境信息。
 *
 * <p>响应里不含任何密钥、密码或连接串，具体约定见 {@link SettingsModels}。
 */
@RestController
@RequestMapping("/api/v1/system")
public class SettingsController {
    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final AiProperties ai;
    private final long accessTokenMinutes;
    private final long autoSubmitIntervalMs;

    public SettingsController(JdbcClient jdbc, DataSource dataSource, AiProperties ai,
            @Value("${app.security.access-token-minutes:60}") long accessTokenMinutes,
            @Value("${app.exam.auto-submit-interval-ms:30000}") long autoSubmitIntervalMs) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.ai = ai;
        this.accessTokenMinutes = accessTokenMinutes;
        this.autoSubmitIntervalMs = autoSubmitIntervalMs;
    }

    /** 汇总五组信息：运行环境、安全参数、考试规则、AI 配置状态、数据库。 */
    @GetMapping("/settings") @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<SettingsView> settings() {
        RuntimeInfo runtime = new RuntimeInfo("smart-exam-backend", SpringBootVersion.getVersion(),
                System.getProperty("java.version"), ZoneId.systemDefault().getId(), Instant.now());
        SecurityInfo security = new SecurityInfo("JWT / HS256", accessTokenMinutes, "BCrypt", false);
        ExamRuleInfo exam = new ExamRuleInfo(autoSubmitIntervalMs,
                GradingService.PASS_RATIO.multiply(new BigDecimal("100")).stripTrailingZeros(),
                "竞赛排名，同分并列且占用名次（1、2、2、4）", "多选题答案集合完全一致才得分，不给部分分");
        AiInfo aiInfo = new AiInfo(ai.configured(), ai.protocol(), ai.normalizedBaseUrl(), ai.model(),
                ai.timeoutSeconds(), ai.maxTokens());
        return ApiResponse.of(new SettingsView(runtime, security, exam, aiInfo, database()));
    }

    /**
     * 读数据库产品、版本和已应用的迁移版本。
     *
     * <p>迁移版本单独 try：自动化测试跑的是 H2 手写 schema，没有 {@code flyway_schema_history} 表，
     * 查不到时返回 {@code null} 而不是让整个接口失败——这个页面的作用是排查环境，
     * 它自己不该因为环境缺一张表就打不开。
     */
    private DatabaseInfo database() {
        String product = "unknown";
        String version = "unknown";
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            product = meta.getDatabaseProductName();
            version = meta.getDatabaseProductVersion();
        } catch (Exception ignored) {
            // 取不到元数据不影响其余信息展示，保持默认的 unknown。
        }
        String schemaVersion = null;
        try {
            schemaVersion = jdbc.sql("SELECT MAX(version) FROM flyway_schema_history WHERE success=1")
                    .query(String.class).optional().orElse(null);
        } catch (Exception ignored) {
            // 没有迁移历史表（如 H2 测试库）时保持 null，由前端显示「未使用 Flyway」。
        }
        return new DatabaseInfo(product, version, schemaVersion, "UTC（连接串强制会话时区）");
    }
}
