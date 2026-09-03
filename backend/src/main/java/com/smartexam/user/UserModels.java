package com.smartexam.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * 用户管理模块的请求响应模型。
 *
 * <p>响应里永远不含 {@code passwordHash}：管理员页面不需要它，返回哈希只会增加泄露面。
 * 重置密码不在本期范围内，因此也没有「读回密码」的场景。
 */
public final class UserModels {
    private UserModels() {}

    /**
     * 新增用户的请求。
     *
     * <p>用户名限定为「字母、数字、下划线、点、减号」并至少 3 位：它同时是登录名，
     * 允许空格或特殊字符只会带来「看起来一样但登录不上」的支持问题。
     *
     * <p>密码只做长度下限校验（8 位），不强制复杂度：课程演示环境里过强的口令规则
     * 反而会让演示账号难以记住。真实部署应当收紧，这一点写在 {@code docs/api.md} 里。
     */
    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 64) @Pattern(regexp = "[A-Za-z0-9_.\\-]+",
                    message = "只能包含字母、数字、下划线、点和减号") String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 64) String displayName,
            @NotNull AppUser.Role role) {}

    /** 启用或禁用用户的请求。 */
    public record StatusRequest(@NotNull AppUser.Status status) {}

    /**
     * 用户视图。
     *
     * @param createdAt 建号时间，管理员页面按它判断哪些是后来补建的账号
     */
    public record UserView(long id, String username, String displayName, AppUser.Role role,
            AppUser.Status status, Instant createdAt) {}
}
