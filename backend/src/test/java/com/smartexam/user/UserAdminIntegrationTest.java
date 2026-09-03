package com.smartexam.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 管理员用户维护的集成测试。
 *
 * <p>本测试类只新建自己的临时账号（{@code temp.*}）并在 {@link AfterEach} 删除，
 * 不改动 {@code data.sql} 里的公共账号：H2 内存库在整个测试 JVM 内共享，
 * 停用一个公共账号会让后面的测试类莫名其妙地拿不到令牌。
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserAdminIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    /** 清掉本测试新建的临时账号，让公共测试数据回到初始状态。 */
    @AfterEach void removeTemporaryAccounts() {
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'temp.%'");
    }

    /**
     * 管理员查询、筛选和新增用户，新账号可以立刻登录。
     *
     * <p>新增后马上用新账号登录一次是必要的：它同时证明密码被正确哈希、
     * 账号默认状态为启用，而不只是「插入了一行数据」。
     */
    @Test void listsFiltersAndCreatesUsers() throws Exception {
        String admin = login("admin");
        // data.sql 共 8 个账号：管理员 1、教师 2、学生 5（其中 1 个停用）。
        getJson("/api/v1/users", admin).andExpect(jsonPath("$.data.total").value(8))
                .andExpect(jsonPath("$.data.page").value(1));
        getJson("/api/v1/users?role=TEACHER", admin).andExpect(jsonPath("$.data.total").value(2));
        getJson("/api/v1/users?status=DISABLED", admin).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].username").value("disabled"));
        // 关键词同时匹配登录名和显示名：student2/3/4 命中登录名，「演示学生」「禁用学生」命中显示名。
        getJson("/api/v1/users?keyword=student", admin).andExpect(jsonPath("$.data.total").value(4));
        getJson("/api/v1/users?keyword=学生", admin).andExpect(jsonPath("$.data.total").value(5));
        // 响应里不能出现任何密码字段。
        getJson("/api/v1/users?size=1", admin).andExpect(jsonPath("$.data.items[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].password").doesNotExist());

        postJson("/api/v1/users", "{\"username\":\"temp.teacher\",\"password\":\"TempPass123!\","
                        + "\"displayName\":\"临时教师\",\"role\":\"TEACHER\"}", admin)
                .andExpect(jsonPath("$.data.username").value("temp.teacher"))
                .andExpect(jsonPath("$.data.role").value("TEACHER"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"temp.teacher\",\"password\":\"TempPass123!\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.user.displayName").value("临时教师"));
    }

    /**
     * 停用账号后旧令牌立即失效，重新启用后又可以登录。
     *
     * <p>「旧令牌立即失效」是本项目刻意付出一次数据库回查换来的性质：
     * JWT 自身无法撤销，如果只读令牌里的角色，被停用的用户在令牌过期前仍能正常操作。
     */
    @Test void disablingUserRevokesExistingTokenAndBlocksLogin() throws Exception {
        String admin = login("admin");
        long id = json.readTree(postJson("/api/v1/users", "{\"username\":\"temp.student\",\"password\":\"TempPass123!\","
                        + "\"displayName\":\"临时学生\",\"role\":\"STUDENT\"}", admin)
                .andReturn().getResponse().getContentAsString()).at("/data/id").asLong();
        String issued = login("temp.student");
        getJson("/api/v1/exams", issued).andExpect(status().isOk());

        patchJson("/api/v1/users/" + id + "/status", "{\"status\":\"DISABLED\"}", admin)
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
        getJson("/api/v1/exams", issued).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"temp.student\",\"password\":\"TempPass123!\"}"))
                .andExpect(status().isUnauthorized());

        patchJson("/api/v1/users/" + id + "/status", "{\"status\":\"ACTIVE\"}", admin)
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"temp.student\",\"password\":\"TempPass123!\"}"))
                .andExpect(status().isOk());
    }

    /**
     * 重名、弱密码、非法枚举、非法分页、停用自己、越权和未登录七条失败路径。
     *
     * <p>「不能停用自己」这条最容易被忽略：单管理员系统里一次误操作就会把系统彻底锁死，
     * 没有任何账号能再启用别人。
     */
    @Test void rejectsDuplicateWeakInvalidAndUnauthorizedRequests() throws Exception {
        String admin = login("admin");
        String body = "{\"username\":\"temp.dup\",\"password\":\"TempPass123!\",\"displayName\":\"重名\",\"role\":\"STUDENT\"}";
        postJson("/api/v1/users", body, admin).andExpect(status().isOk());
        postJson("/api/v1/users", body, admin).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_EXISTS"));
        postJson("/api/v1/users", "{\"username\":\"temp.weak\",\"password\":\"123\",\"displayName\":\"弱密码\","
                        + "\"role\":\"STUDENT\"}", admin)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        postJson("/api/v1/users", "{\"username\":\"temp bad\",\"password\":\"TempPass123!\",\"displayName\":\"空格\","
                        + "\"role\":\"STUDENT\"}", admin)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        postJson("/api/v1/users", "{\"username\":\"temp.role\",\"password\":\"TempPass123!\",\"displayName\":\"错角色\","
                        + "\"role\":\"PRINCIPAL\"}", admin)
                .andExpect(status().isBadRequest());
        getJson("/api/v1/users?page=0", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        getJson("/api/v1/users?size=101", admin).andExpect(status().isBadRequest());
        getJson("/api/v1/users?role=PRINCIPAL", admin).andExpect(status().isBadRequest());

        // 管理员在 data.sql 里是 1 号账号，停用自己必须被拒绝。
        patchJson("/api/v1/users/1/status", "{\"status\":\"DISABLED\"}", admin)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CANNOT_DISABLE_SELF"));
        patchJson("/api/v1/users/999999/status", "{\"status\":\"DISABLED\"}", admin)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        getJson("/api/v1/users", login("teacher")).andExpect(status().isForbidden());
        getJson("/api/v1/users", login("student")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/users")).andExpect(status().isUnauthorized());
    }

    /** 发一个带令牌的 GET。 */
    private ResultActions getJson(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token));
    }
    /** 发一个带令牌的 POST。 */
    private ResultActions postJson(String path, String body, String token) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    /** 发一个带令牌的 PATCH。 */
    private ResultActions patchJson(String path, String body, String token) throws Exception {
        return mockMvc.perform(patch(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    /** 以指定账号登录并返回访问令牌。 */
    private String login(String username) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\""
                                + (username.startsWith("temp.") ? "TempPass123!" : "ExamDemo123!") + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).at("/data/accessToken").asText();
    }
}
