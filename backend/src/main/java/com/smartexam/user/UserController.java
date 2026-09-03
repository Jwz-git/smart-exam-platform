package com.smartexam.user;

import com.smartexam.common.ApiResponse;
import com.smartexam.common.PageResult;
import com.smartexam.user.UserModels.CreateUserRequest;
import com.smartexam.user.UserModels.StatusRequest;
import com.smartexam.user.UserModels.UserView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 用户管理接口，仅管理员可访问（角色限制在 {@code SecurityConfig} 里按 URL 前缀配置）。
 *
 * <p>{@link Validated} 让 {@code @Min}、{@code @Max} 在方法参数上生效，非法分页参数因此返回 400
 * 而不是 500；请求体的字段校验由 {@link Valid} 触发，两者都走同一个全局异常处理器。
 */
@Validated
@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService service;

    public UserController(UserService service) { this.service = service; }

    /** 分页查询用户，支持按登录名/显示名关键词、角色和状态筛选。{@code size} 上限 100。 */
    @GetMapping
    public ApiResponse<PageResult<UserView>> list(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) AppUser.Role role,
            @RequestParam(required = false) AppUser.Status status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.of(service.list(keyword, role, status, page, size));
    }

    /** 新增用户。用户名重复返回 409；密码由后端哈希后存储，响应里不含任何密码字段。 */
    @PostMapping
    public ApiResponse<UserView> create(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.of(service.create(request));
    }

    /** 启用或停用用户。不允许停用当前登录的管理员本人，避免把系统彻底锁死。 */
    @PatchMapping("/{id}/status")
    public ApiResponse<UserView> updateStatus(@PathVariable long id, @Valid @RequestBody StatusRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.updateStatus(id, request.status(), userId(jwt)));
    }

    /** 从令牌读取当前管理员 ID，用于拦住「停用自己」。 */
    private long userId(Jwt jwt) { return ((Number) jwt.getClaim("userId")).longValue(); }
}
