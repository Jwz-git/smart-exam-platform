package com.smartexam.user;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final JdbcClient jdbcClient;

    public UserRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

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
}
