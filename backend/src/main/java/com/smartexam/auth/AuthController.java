package com.smartexam.auth;

import com.smartexam.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：登录取令牌、查询当前用户、退出。
 *
 * <p>{@link ConditionalOnWebApplication} 的作用是让本类只在以 Web 方式启动时注册。初始化脚本
 * {@code scripts/init-local.sh} 会用 {@code --spring.main.web-application-type=none} 启动一次应用，
 * 目的只是跑 Flyway 迁移；那种模式下没有 HTTP 端点，也就不需要（同时也没有配置）JWT 密钥。
 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 校验账号密码并签发访问令牌。失败统一返回 401，不区分「用户不存在」和「密码错误」。 */
    @PostMapping("/login")
    public ApiResponse<AuthService.LoginResult> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.of(authService.login(request.username(), request.password()));
    }

    /**
     * 返回当前登录用户。前端启动时用它校验本地缓存的令牌是否仍然有效，
     * 同时纠正被手工改过的 localStorage 角色信息。
     */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(Authentication authentication) {
        return ApiResponse.of(authService.currentUser(authentication.getName()));
    }

    /**
     * 退出登录，返回 204。
     *
     * <p>JWT 是无状态的，服务端没有会话可以销毁，因此这里不做任何事，由客户端删除本地令牌。
     * 当前 MVP 不维护令牌吊销名单，已签发的令牌在到期前仍具备密码学有效性——这是有意的取舍，
     * 已在 {@code docs/api.md} 第 3 节写明；被停用的账号则会在鉴权阶段被拦下。
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }

    /** 登录请求体。两个字段都不允许为空白，校验失败由全局处理器转成 400。 */
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
}
