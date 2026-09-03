package com.smartexam.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.user.AppUser;
import com.smartexam.user.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 安全配置：无状态 JWT 资源服务器 + 按 URL 的角色规则 + 统一的 401/403 响应体。
 *
 * <p>分三层防护，缺一不可：
 * <ol>
 *   <li>本类按 URL 前缀限定角色，挡住明显越权的请求；</li>
 *   <li>Controller 上的 {@code @PreAuthorize}（由 {@link EnableMethodSecurity} 生效）
 *       处理同一前缀下不同方法角色不同的情况，例如考试接口教师和学生都能访问但能做的事不同；</li>
 *   <li>Service 层再判断「资源归属」——角色对了也不等于能动别人的数据。</li>
 * </ol>
 * 前端隐藏按钮只是体验优化，不构成任何权限保证。
 *
 * <p>密码哈希器不在本类里定义，见 {@link PasswordConfig}：本类只在 Web 模式下生效，
 * 而跑迁移用的非 Web 启动模式同样需要 {@code PasswordEncoder}。
 *
 * <p>注意 {@code anyRequest().authenticated()} 的含义：新增的接口路径若没在上面列出，
 * 默认只要求「已登录」。因此新增接口时必须同步补 URL 规则或方法级注解，
 * 否则会出现学生也能调用教师接口的情况。
 */
@Configuration
@EnableMethodSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter,
            ObjectMapper objectMapper) throws Exception {
        return http
                // 纯令牌鉴权、无 Cookie 会话，不存在 CSRF 的前提条件，因此关闭 CSRF 过滤。
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/users/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/knowledge-points/**", "/api/v1/questions/**").hasRole("TEACHER")
                        .requestMatchers("/api/v1/papers/**").hasRole("TEACHER")
                        // AI 只生成草稿、不写库，但仍限定教师：它会消耗模型额度，也不该对学生开放。
                        .requestMatchers("/api/v1/ai/**").hasRole("TEACHER")
                        // 统计分析只读教师本人的题库与考试数据，范围取自令牌而不是查询参数。
                        .requestMatchers("/api/v1/stats/**").hasRole("TEACHER")
                        // 系统设置返回版本、时区和 AI 配置状态（不含密钥），教师和管理员都要用它核对环境。
                        .requestMatchers("/api/v1/system/**").hasAnyRole("TEACHER", "ADMIN")
                        // 主观题评分只有教师会调用，直接在 URL 层限死。
                        .requestMatchers("/api/v1/submission-answers/**").hasRole("TEACHER")
                        // /my/** 一律是「当前登录学生本人」的资源，路径里不带学生 ID。
                        .requestMatchers("/api/v1/my/**").hasRole("STUDENT")
                        // 答卷路径下读写角色不同：教师要读答卷详情来阅卷，写答案和交卷只能是学生。
                        // 这里只放行到「教师或学生」，具体区分由 SubmissionController 的方法级注解完成。
                        .requestMatchers("/api/v1/submissions/**").hasAnyRole("TEACHER", "STUDENT")
                        .requestMatchers("/api/v1/exams/**").hasAnyRole("TEACHER", "STUDENT")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint((request, response, exception) ->
                                writeSecurityError(response, objectMapper, 401, "UNAUTHORIZED", "请先登录或重新登录"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeSecurityError(response, objectMapper, 403, "FORBIDDEN", "没有权限执行此操作")))
                // oauth2ResourceServer 只覆盖携带令牌的失败场景，未携带令牌时由 exceptionHandling 兜底，
                // 两处都指向同一个写法，保证 401/403 的响应结构和业务错误完全一致。
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeSecurityError(response, objectMapper, 401, "UNAUTHORIZED", "请先登录或重新登录"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeSecurityError(response, objectMapper, 403, "FORBIDDEN", "没有权限执行此操作")))
                .build();
    }

    /**
     * 从环境变量读取签名密钥。
     *
     * <p>{@code application.yml} 里刻意没有给 {@code JWT_SECRET} 默认值：缺少密钥时应用直接启动失败，
     * 而不是悄悄用一个仓库里人人可见的弱密钥继续跑。HS256 要求密钥不短于 32 字节，这里显式校验。
     */
    @Bean
    SecretKey jwtSecretKey(@Value("${app.security.jwt-secret}") String secret) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must contain at least 32 bytes");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key) {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /**
     * 把令牌换成认证对象，同时每次请求都回查一次用户表。
     *
     * <p>多这一次查询是为了让「停用账号」立刻生效：角色写在令牌里，如果只读令牌，
     * 被停用的用户在令牌过期前仍能正常操作。回查后账号不存在或已停用即抛
     * {@link InvalidBearerTokenException}，返回 401。
     */
    @Bean
    Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter(UserRepository users) {
        return jwt -> {
            AppUser user = users.findByUsername(jwt.getSubject())
                    .filter(candidate -> candidate.status() == AppUser.Status.ACTIVE)
                    .orElseThrow(() -> new InvalidBearerTokenException("账号已禁用或不存在"));
            return new JwtAuthenticationToken(jwt,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())), user.username());
        };
    }

    /**
     * 直接写出统一结构的安全错误响应。
     *
     * <p>401/403 发生在过滤器链上，还没进入 {@code @RestControllerAdvice} 的处理范围，
     * 所以这里手工序列化，字段与业务错误保持一致，前端不必区分两套错误格式。
     */
    private void writeSecurityError(HttpServletResponse response, ObjectMapper objectMapper,
            int status, String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "code", code, "message", message, "fieldErrors", Map.of(),
                "requestId", UUID.randomUUID().toString()));
    }
}
