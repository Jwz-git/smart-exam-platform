package com.smartexam.system;

import com.smartexam.ai.AiProperties;
import com.smartexam.common.ApiResponse;
import com.smartexam.common.SettingsCatalog;
import com.smartexam.common.SettingsStore;
import com.smartexam.system.SettingsModels.AiInfo;
import com.smartexam.system.SettingsModels.DatabaseInfo;
import com.smartexam.system.SettingsModels.ExamRuleInfo;
import com.smartexam.system.SettingsModels.RuntimeInfo;
import com.smartexam.system.SettingsModels.SecurityInfo;
import com.smartexam.system.SettingsModels.SettingItemView;
import com.smartexam.system.SettingsModels.SettingsUpdateResult;
import com.smartexam.system.SettingsModels.SettingsView;
import com.smartexam.system.SettingsModels.UpdateSettingsRequest;
import jakarta.validation.Valid;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootVersion;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统设置接口：读当前生效的运行参数，并允许管理员修改其中白名单内的若干项。
 *
 * <p>与 {@code /api/health} 的区别是「谁能看」：健康检查匿名可访问，因此只回状态和时间；
 * 本接口需要登录且限定教师或管理员，才敢返回版本号、数据库产品和 AI 服务商这类环境信息。
 *
 * <p>读写权限刻意不同，也是本项目三层权限的一个典型例子：
 * URL 规则（{@code SecurityConfig}）放行到「教师或管理员」，方法级注解再把写操作收紧到管理员。
 * 教师能看到当前生效的参数（演示和排查都需要），但改不了。
 *
 * <p>响应里不含任何密钥、密码或连接串，可编辑白名单也不含它们，具体约定见 {@link SettingsModels}
 * 与 {@link SettingsCatalog}。
 */
@RestController
@RequestMapping("/api/v1/system")
public class SettingsController {
    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final AiProperties ai;
    private final SettingsStore settings;
    private final long autoSubmitIntervalMs;

    public SettingsController(JdbcClient jdbc, DataSource dataSource, AiProperties ai, SettingsStore settings,
            @Value("${app.exam.auto-submit-interval-ms:30000}") long autoSubmitIntervalMs) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.ai = ai;
        this.settings = settings;
        this.autoSubmitIntervalMs = autoSubmitIntervalMs;
    }

    /** 汇总可编辑项与五组只读信息：运行环境、安全参数、考试规则、AI 配置状态、数据库。 */
    @GetMapping("/settings") @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<SettingsView> settings() {
        return ApiResponse.of(view());
    }

    /**
     * 修改设置。仅管理员，且只接受 {@link SettingsCatalog} 白名单里的键。
     *
     * <p>整批校验后再写库（见 {@code SettingsStore#apply}），因此一次提交要么全部生效、
     * 要么一项都不变，不会留下改了一半的状态。返回修改后的完整视图，前端不必再取一次。
     */
    @PutMapping("/settings") @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<SettingsUpdateResult> update(@Valid @RequestBody UpdateSettingsRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        int changed = settings.apply(request.values(), ((Number) jwt.getClaim("userId")).longValue());
        return ApiResponse.of(new SettingsUpdateResult(changed, view()));
    }

    /** 组装整页视图。读与写两个接口都用它，保证「改完之后看到的」和「重新打开看到的」完全一致。 */
    private SettingsView view() {
        RuntimeInfo runtime = new RuntimeInfo("smart-exam-backend", SpringBootVersion.getVersion(),
                System.getProperty("java.version"), ZoneId.systemDefault().getId(), Instant.now());
        SecurityInfo security = new SecurityInfo("JWT / HS256",
                settings.asInt(SettingsCatalog.ACCESS_TOKEN_MINUTES), "BCrypt", false);
        ExamRuleInfo exam = new ExamRuleInfo(autoSubmitIntervalMs,
                settings.asDecimal(SettingsCatalog.PASS_RATIO_PERCENT).stripTrailingZeros(),
                "竞赛排名，同分并列且占用名次（1、2、2、4）", "多选题答案集合完全一致才得分，不给部分分");
        AiInfo aiInfo = new AiInfo(ai.configured(), ai.protocol(), ai.normalizedBaseUrl(), ai.model(),
                ai.timeoutSeconds(), ai.maxTokens());
        return new SettingsView(editable(), runtime, security, exam, aiInfo, database());
    }

    /** 把设置项摊平成前端直接可渲染的形状，顺序与 {@link SettingsCatalog#DEFINITIONS} 一致。 */
    private List<SettingItemView> editable() {
        return settings.items().stream().map(item -> new SettingItemView(item.definition().key(),
                item.definition().label(), item.definition().group(), item.definition().type().name(),
                item.value(), item.defaultValue(), item.overridden(), item.definition().min(),
                item.definition().max(), item.definition().unit(), item.definition().description(),
                item.updatedAt(), item.updatedBy())).toList();
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
