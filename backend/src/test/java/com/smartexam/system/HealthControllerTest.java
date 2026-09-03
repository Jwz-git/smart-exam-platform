package com.smartexam.system;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 健康检查接口测试。
 *
 * <p>用 {@link WebMvcTest} 只加载 Web 层，不启动数据源——这个接口不碰数据库，
 * 没必要为它准备一套数据。{@code addFilters = false} 关掉安全过滤器链，
 * 这样测试聚焦在「接口返回什么」，而「该接口是否放行」由带完整上下文的
 * 集成测试覆盖。
 */
@WebMvcTest(HealthController.class)
@AutoConfigureMockMvc(addFilters = false)
class HealthControllerTest {
    @Autowired private MockMvc mockMvc;

    /** 探活接口返回 UP 和服务名，供部署脚本判断服务是否就绪。 */
    @Test
    void returnsServiceHealth() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("smart-exam-backend"));
    }
}
