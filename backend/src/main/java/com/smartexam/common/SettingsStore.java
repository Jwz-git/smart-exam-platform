package com.smartexam.common;

import com.smartexam.common.SettingsCatalog.Definition;
import com.smartexam.common.SettingsCatalog.ValueType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 系统设置的读写与生效点。
 *
 * <p>模型只有一句话：<b>环境变量是默认值，{@code system_setting} 表是覆盖层。</b>
 * 读一个键时先看表里有没有覆盖，没有就用启动时注入的默认值；「恢复默认」实现为删除那一行，
 * 而不是写回一个当时的默认值——否则以后改了环境变量，界面上那一行还压着旧值。
 *
 * <p>这样设计正好解决了「界面能改配置」与「显示值必须等于生效值」的矛盾：
 * 能改的只有本进程每次用到时都会重新读取的项（白名单见 {@link SettingsCatalog}），
 * 读取路径就是生效路径，因此不存在改完不生效的情况；而启动时一次性固化的参数
 * （调度间隔、HTTP 超时、密钥、连接串）根本不进白名单。
 *
 * <p>覆盖值缓存在内存里，写入后立即失效重载。单实例部署下这就够了；
 * 若将来多实例，需要改成带过期时间的缓存或写入后广播——这一点写在这里而不是藏起来。
 */
@Component
public class SettingsStore {
    private static final Logger log = LoggerFactory.getLogger(SettingsStore.class);
    /** 百分比换算成比例时保留的小数位，4 位足以表达 0.5% 这类精度。 */
    private static final int RATIO_SCALE = 4;

    private final JdbcClient jdbc;
    /** 启动时注入的默认值，键与 {@link SettingsCatalog} 一致。 */
    private final Map<String, String> defaults;
    /** 数据库覆盖层的内存缓存；{@code null} 表示还没读过，读取时惰性加载。 */
    private volatile Map<String, String> overrides;

    /**
     * 默认值全部来自 Spring 属性（也就是环境变量），在这里集中收敛成一张表。
     *
     * <p>惰性加载而不是构造时就查库：Flyway 迁移与本 Bean 的创建顺序并不由本类保证，
     * 而 {@code scripts/init-local.sh} 还会用非 Web 模式只跑一次迁移。第一次真正读设置时
     * 表一定已经建好了。
     */
    public SettingsStore(JdbcClient jdbc,
            @Value("${app.exam.pass-ratio-percent:60}") String passRatioPercent,
            @Value("${app.security.access-token-minutes:60}") String accessTokenMinutes,
            @Value("${app.question.import-max-rows:200}") String importMaxRows,
            @Value("${app.question.import-skip-duplicate-stem:true}") String importSkipDuplicate,
            @Value("${app.paper.auto-compose-max-questions:50}") String autoComposeMaxQuestions,
            @Value("${app.practice.batch-size:10}") String practiceBatchSize) {
        this.jdbc = jdbc;
        Map<String, String> values = new LinkedHashMap<>();
        values.put(SettingsCatalog.PASS_RATIO_PERCENT, passRatioPercent);
        values.put(SettingsCatalog.ACCESS_TOKEN_MINUTES, accessTokenMinutes);
        values.put(SettingsCatalog.IMPORT_MAX_ROWS, importMaxRows);
        values.put(SettingsCatalog.IMPORT_SKIP_DUPLICATE, importSkipDuplicate);
        values.put(SettingsCatalog.AUTO_COMPOSE_MAX_QUESTIONS, autoComposeMaxQuestions);
        values.put(SettingsCatalog.PRACTICE_BATCH_SIZE, practiceBatchSize);
        this.defaults = Map.copyOf(values);
    }

    /** 当前生效的整数值。 */
    public int asInt(String key) { return asDecimal(key).intValue(); }

    /** 当前生效的小数值。 */
    public BigDecimal asDecimal(String key) { return new BigDecimal(effective(key)); }

    /** 当前生效的布尔值。 */
    public boolean asBoolean(String key) { return Boolean.parseBoolean(effective(key)); }

    /**
     * 及格线占试卷总分的比例（例如 60% 返回 {@code 0.6000}）。
     *
     * <p>成绩排名的及格率与统计分析的及格分数线都调用这一个方法，
     * 保证两个页面永远不会出现两条不同的及格线。
     */
    public BigDecimal passRatio() {
        return asDecimal(SettingsCatalog.PASS_RATIO_PERCENT)
                .divide(new BigDecimal("100"), RATIO_SCALE, RoundingMode.HALF_UP);
    }

    /** 当前生效值的原始文本：有覆盖用覆盖，否则用默认值。 */
    private String effective(String key) {
        String override = load().get(key);
        return override != null ? override : requireDefault(key);
    }

    private String requireDefault(String key) {
        String value = defaults.get(key);
        if (value == null) throw new IllegalArgumentException("未定义的设置项：" + key);
        return value;
    }

    /**
     * 全部设置项的当前状态，供系统设置页展示。
     *
     * <p>同时给出当前值、默认值和来源，管理员因此能一眼看出哪几项被改过、
     * 以及改回默认会变成什么——只显示当前值的话，「默认是多少」就只能去翻文档。
     */
    public List<Item> items() {
        Map<String, String> current = load();
        Map<String, Meta> meta = loadMeta();
        List<Item> items = new ArrayList<>(SettingsCatalog.DEFINITIONS.size());
        for (Definition definition : SettingsCatalog.DEFINITIONS) {
            String override = current.get(definition.key());
            Meta detail = meta.get(definition.key());
            items.add(new Item(definition, override != null ? override : requireDefault(definition.key()),
                    requireDefault(definition.key()), override != null,
                    detail == null ? null : detail.updatedAt(), detail == null ? null : detail.updatedBy()));
        }
        return items;
    }

    /**
     * 批量写入设置。值为 {@code null} 或空串表示恢复默认（删除覆盖行）。
     *
     * <p>先把所有键值整批校验完再写第一行：一次提交里如果第 3 项越界，前两项也不应该已经生效，
     * 否则管理员会得到一个「改了一半」的状态。加上事务与整批校验，结果只有全成或全不成两种。
     *
     * @return 实际发生变化的键数量
     */
    @Transactional
    public int apply(Map<String, String> values, long userId) {
        Map<String, String> normalized = new LinkedHashMap<>();
        values.forEach((key, raw) -> {
            Definition definition = SettingsCatalog.find(key).orElseThrow(() -> new DomainException(
                    HttpStatus.BAD_REQUEST, "SETTING_UNKNOWN", "不支持修改的设置项：" + key));
            normalized.put(key, raw == null || raw.isBlank() ? null : normalize(definition, raw.trim()));
        });
        Map<String, String> current = load();
        Instant now = Instant.now();
        int changed = 0;
        for (Map.Entry<String, String> entry : normalized.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            String before = current.get(key);
            if (value == null) {
                if (before == null) continue;
                jdbc.sql("DELETE FROM system_setting WHERE setting_key=:key").param("key", key).update();
                changed++;
            } else {
                if (value.equals(before)) continue;
                write(key, value, userId, now);
                changed++;
            }
        }
        if (changed > 0) overrides = null;
        return changed;
    }

    /** 先更新、更新不到再插入。两条语句而不是数据库方言的 upsert，避免绑死 MySQL。 */
    private void write(String key, String value, long userId, Instant at) {
        int updated = jdbc.sql("""
                UPDATE system_setting SET setting_value=:value,updated_by=:user,updated_at=:at
                WHERE setting_key=:key
                """).param("value", value).param("user", userId).param("at", at).param("key", key).update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO system_setting(setting_key,setting_value,updated_by,updated_at)
                    VALUES (:key,:value,:user,:at)
                    """).param("key", key).param("value", value).param("user", userId).param("at", at).update();
        }
    }

    /**
     * 按类型解析并做范围校验，返回规范化后的存储文本。
     *
     * <p>规范化很关键：{@code "60.0"} 与 {@code "60"}、{@code "TRUE"} 与 {@code "true"}
     * 必须落成同一个字符串，否则「和当前值相同」这个判断会误判成一次修改。
     */
    private String normalize(Definition definition, String raw) {
        if (definition.type() == ValueType.BOOLEAN) {
            if (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false")) return raw.toLowerCase();
            throw invalid(definition, "只能填 true 或 false");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw);
        } catch (NumberFormatException exception) {
            throw invalid(definition, "「" + raw + "」不是数字");
        }
        if (definition.type() == ValueType.INTEGER && value.stripTrailingZeros().scale() > 0) {
            throw invalid(definition, "必须是整数");
        }
        if (value.compareTo(definition.min()) < 0 || value.compareTo(definition.max()) > 0) {
            throw invalid(definition, "取值范围是 " + plain(definition.min()) + "—" + plain(definition.max())
                    + definition.unit());
        }
        return definition.type() == ValueType.INTEGER
                ? String.valueOf(value.intValue())
                : plain(value.stripTrailingZeros());
    }

    /** 去掉科学计数法，保证存进数据库的是人能读的十进制文本。 */
    private String plain(BigDecimal value) { return value.toPlainString(); }

    private DomainException invalid(Definition definition, String reason) {
        return new DomainException(HttpStatus.BAD_REQUEST, "SETTING_INVALID",
                "「" + definition.label() + "」" + reason);
    }

    /**
     * 读覆盖层，带内存缓存。
     *
     * <p>读不到表时退回全默认并只记一条警告：设置是全局读取路径上的东西，
     * 让阅卷、导入这些主流程因为缺一张覆盖表而整体失败，代价明显大于「按默认值继续跑」。
     */
    private Map<String, String> load() {
        Map<String, String> cached = overrides;
        if (cached != null) return cached;
        Map<String, String> loaded = new HashMap<>();
        try {
            jdbc.sql("SELECT setting_key,setting_value FROM system_setting")
                    .query((rs, row) -> Map.entry(rs.getString("setting_key"), rs.getString("setting_value")))
                    .list().forEach(entry -> loaded.put(entry.getKey(), entry.getValue()));
        } catch (Exception exception) {
            log.warn("读取 system_setting 失败，本次按默认值运行：{}", exception.getMessage());
        }
        Map<String, String> snapshot = Map.copyOf(loaded);
        overrides = snapshot;
        return snapshot;
    }

    /** 读覆盖行的修改人与修改时间，仅用于界面展示，失败时按「无记录」处理。 */
    private Map<String, Meta> loadMeta() {
        Map<String, Meta> meta = new HashMap<>();
        try {
            jdbc.sql("""
                    SELECT s.setting_key,s.updated_at,u.display_name
                    FROM system_setting s LEFT JOIN app_user u ON u.id=s.updated_by
                    """).query((rs, row) -> Map.entry(rs.getString("setting_key"),
                            new Meta(rs.getTimestamp("updated_at") == null ? null
                                    : rs.getTimestamp("updated_at").toInstant(), rs.getString("display_name"))))
                    .list().forEach(entry -> meta.put(entry.getKey(), entry.getValue()));
        } catch (Exception exception) {
            log.warn("读取 system_setting 修改记录失败：{}", exception.getMessage());
        }
        return meta;
    }

    /** 覆盖行的修改痕迹。 */
    private record Meta(Instant updatedAt, String updatedBy) {}

    /**
     * 一个设置项的完整状态。
     *
     * @param overridden {@code true} 表示当前值来自数据库覆盖，{@code false} 表示来自环境变量默认值
     */
    public record Item(Definition definition, String value, String defaultValue, boolean overridden,
            Instant updatedAt, String updatedBy) {}
}
