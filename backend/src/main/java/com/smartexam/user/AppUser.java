package com.smartexam.user;

/**
 * 系统用户。三种角色的权限边界见 {@code plan.md} 第 2.1 节。
 *
 * <p>{@code passwordHash} 只保存 BCrypt 哈希，不保存明文；该字段仅在登录校验时使用，
 * 不会出现在任何对外响应里。
 *
 * <p>用户不做物理删除：存在答卷的账号只允许停用（{@link Status#DISABLED}），以保留成绩的可追溯性。
 * 被停用的账号既不能登录，也不能继续使用此前签发的 JWT，判断点在
 * {@code SecurityConfig#jwtAuthenticationConverter}。
 */
public record AppUser(long id, String username, String passwordHash, String displayName, Role role, Status status) {
    /** 角色：管理员维护账号，教师维护题库与考试，学生答题查分。 */
    public enum Role { ADMIN, TEACHER, STUDENT }

    /** 账号状态。停用后保留历史数据，但不能登录也不能通过鉴权。 */
    public enum Status { ACTIVE, DISABLED }
}
