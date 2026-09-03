package com.smartexam.user;

import com.smartexam.common.PageResult;
import com.smartexam.user.UserModels.CreateUserRequest;
import com.smartexam.user.UserModels.UserView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 用户表读写。项目使用 Spring JDBC 而不是 JPA：SQL 全部显式可见，便于在课程报告里说明
 * 每条查询做了什么，也避免 N+1 之类隐式行为。
 *
 * <p>所有查询都用具名参数占位符，不做字符串拼接，从根本上排除 SQL 注入。
 */
@Repository
public class UserRepository {
    private final JdbcClient jdbcClient;

    public UserRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 按用户名查询用户，不存在时返回空。
     *
     * <p>这里不过滤 {@code status}：调用方需要区分「账号不存在」和「账号被停用」两种情况，
     * 状态判断留给 {@code AuthService} 和鉴权转换器各自处理。
     */
    public Optional<AppUser> findByUsername(String username) {
        return jdbcClient.sql("""
                SELECT id, username, password_hash, display_name, role, status
                FROM app_user WHERE username = :username
                """)
                .param("username", username)
                .query((rs, rowNum) -> new AppUser(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("display_name"),
                        AppUser.Role.valueOf(rs.getString("role")),
                        AppUser.Status.valueOf(rs.getString("status"))))
                .optional();
    }

    /**
     * 分页查询用户，支持关键词、角色和状态筛选。
     *
     * <p>{@code keyword} 同时匹配登录名和显示名，并对 {@code %} 与 {@code _} 做转义，
     * 让它们按普通字符参与匹配——否则用户搜一个下划线会命中所有账号。
     *
     * <p>条件用「参数为空则该条件恒真」的写法拼在 SQL 里，而不是动态拼字符串：
     * SQL 文本固定不变，既排除注入，也让数据库能复用执行计划。
     */
    public PageResult<UserView> findPage(String keyword, AppUser.Role role, AppUser.Status status, int page, int size) {
        String filter = """
                FROM app_user
                WHERE (:keyword IS NULL OR username LIKE :like ESCAPE '!' OR display_name LIKE :like ESCAPE '!')
                  AND (:role IS NULL OR role = :role) AND (:status IS NULL OR status = :status)
                """;
        String normalized = keyword == null || keyword.isBlank() ? null : keyword.trim();
        String like = normalized == null ? null : "%" + escapeLike(normalized) + "%";
        long total = jdbcClient.sql("SELECT COUNT(*) " + filter)
                .param("keyword", normalized).param("like", like)
                .param("role", role == null ? null : role.name())
                .param("status", status == null ? null : status.name())
                .query(Long.class).single();
        List<UserView> items = jdbcClient.sql("SELECT id,username,display_name,role,status,created_at " + filter
                        + " ORDER BY id LIMIT :size OFFSET :offset")
                .param("keyword", normalized).param("like", like)
                .param("role", role == null ? null : role.name())
                .param("status", status == null ? null : status.name())
                .param("size", size).param("offset", (long) (page - 1) * size)
                .query(this::mapUser).list();
        return new PageResult<>(items, page, size, total);
    }

    /** 按 ID 查用户视图，用于状态变更后回显。 */
    public Optional<UserView> findViewById(long id) {
        return jdbcClient.sql("SELECT id,username,display_name,role,status,created_at FROM app_user WHERE id=:id")
                .param("id", id).query(this::mapUser).optional();
    }

    /** 写入新用户，返回主键。密码哈希由 Service 生成，本类不接触明文密码。 */
    public long create(CreateUserRequest request, String passwordHash) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcClient.sql("""
                INSERT INTO app_user(username,password_hash,display_name,role,status)
                VALUES (:username,:hash,:name,:role,'ACTIVE')
                """).param("username", request.username().trim()).param("hash", passwordHash)
                .param("name", request.displayName().trim()).param("role", request.role().name())
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    /** 启用或禁用用户。停用后既不能登录，也不能继续使用此前签发的令牌。 */
    public void updateStatus(long id, AppUser.Status status) {
        jdbcClient.sql("UPDATE app_user SET status=:status WHERE id=:id")
                .param("status", status.name()).param("id", id).update();
    }

    /** 转义 LIKE 通配符，escape 字符与题库检索统一用 '!'，见 {@code QuestionRepository#escapeLike}。 */
    private String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private UserView mapUser(ResultSet rs, int row) throws SQLException {
        var createdAt = rs.getTimestamp("created_at");
        return new UserView(rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                AppUser.Role.valueOf(rs.getString("role")), AppUser.Status.valueOf(rs.getString("status")),
                createdAt == null ? null : createdAt.toInstant());
    }
}
