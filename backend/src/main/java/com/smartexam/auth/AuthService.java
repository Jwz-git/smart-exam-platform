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

    public Map<String, Object> currentUser(String username) {
        AppUser user = users.findByUsername(username)
                .filter(candidate -> candidate.status() == AppUser.Status.ACTIVE)
                .orElseThrow(() -> new BadCredentialsException("账号已失效"));
        return toUserView(user);
    }

    private Map<String, Object> toUserView(AppUser user) {
        return Map.of("id", user.id(), "username", user.username(), "displayName", user.displayName(),
                "role", user.role().name());
    }

    public record LoginResult(String accessToken, String tokenType, Instant expiresAt, Map<String, Object> user) {}
}
