package com.smartexam.auth;

import com.smartexam.user.AppUser;
import com.smartexam.user.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

/**
 * 登录与当前用户查询。
 *
 * <p>安全上的三条硬性要求（见 {@code AGENTS.md} 第 5 节）在这里落地：
 * 密码只比对 BCrypt 哈希、不解密也不回显；令牌由服务端签发并带明确过期时间；
 * 被停用的账号一律拒绝，且失败信息不透露账号是否存在。
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final long accessTokenMinutes;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder,
            @Value("${app.security.access-token-minutes}") long accessTokenMinutes) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.accessTokenMinutes = accessTokenMinutes;
    }

    /**
     * 校验账号密码并签发 HS256 访问令牌。
     *
     * <p>「账号不存在」「密码错误」「账号被停用」三种情况都返回同一句“账号或密码错误”，
     * 避免登录接口变成枚举有效账号的工具。
     *
     * <p>令牌里额外放了 {@code userId} 和 {@code role}：后续每个接口都要按「资源归属」判断
     * （例如教师只能改自己创建的题目），带上 userId 可以省掉一次按用户名反查。
     */
    public LoginResult login(String username, String password) {
        AppUser user = users.findByUsername(username)
                .filter(candidate -> candidate.status() == AppUser.Status.ACTIVE)
                .orElseThrow(() -> new BadCredentialsException("账号或密码错误"));
        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new BadCredentialsException("账号或密码错误");
        }
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(accessTokenMinutes, ChronoUnit.MINUTES);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("smart-exam-backend")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(user.username())
                .claim("userId", user.id())
                .claim("displayName", user.displayName())
                .claim("role", user.role().name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new LoginResult(token, "Bearer", expiresAt, toUserView(user));
    }

    /** 返回当前登录用户；令牌仍在有效期但账号已被停用时同样拒绝。 */
    public Map<String, Object> currentUser(String username) {
        AppUser user = users.findByUsername(username)
                .filter(candidate -> candidate.status() == AppUser.Status.ACTIVE)
                .orElseThrow(() -> new BadCredentialsException("账号已失效"));
        return toUserView(user);
    }

    /** 只暴露前端需要的四个字段，确保密码哈希和账号状态不会随响应外泄。 */
    private Map<String, Object> toUserView(AppUser user) {
        return Map.of("id", user.id(), "username", user.username(), "displayName", user.displayName(),
                "role", user.role().name());
    }

    /**
     * 登录结果。
     *
     * @param accessToken 访问令牌，后续请求放在 {@code Authorization: Bearer <token>} 头里
     * @param tokenType   固定为 {@code Bearer}
     * @param expiresAt   过期时间，前端可据此提前提示重新登录
     * @param user        当前用户的公开信息
     */
    public record LoginResult(String accessToken, String tokenType, Instant expiresAt, Map<String, Object> user) {}
}
