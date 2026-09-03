package com.smartexam.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码哈希器的独立配置。
 *
 * <p>刻意不放在 {@link SecurityConfig} 里：那个类带
 * {@code @ConditionalOnWebApplication(SERVLET)}，只在以 Web 方式启动时生效。而
 * {@code scripts/init-local.sh} 会用 {@code --spring.main.web-application-type=none}
 * 启动一次应用来跑 Flyway 迁移，那种模式下如果 {@link PasswordEncoder} 不存在，
 * 任何需要它的业务 Bean（如 {@code UserService}）都会启动失败，一键初始化脚本随之中断。
 *
 * <p>密码哈希本身也不是 Web 关注点：无论以什么方式启动，只要会创建用户就需要它。
 */
@Configuration
public class PasswordConfig {
    /** BCrypt 自带随机盐并可调工作因子，适合存储密码；不使用 MD5、SHA 这类快速摘要算法。 */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
