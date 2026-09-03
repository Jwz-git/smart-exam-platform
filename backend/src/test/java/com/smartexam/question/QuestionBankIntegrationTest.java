package com.smartexam.question;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 题库与知识点的集成测试，对应验收用例 1「教师新增不同题型的题目并按条件查询」。
 *
 * <p>覆盖四条规则：知识点的归属校验、四种基础题型的创建与筛选、
 * 题目编辑与删除、以及未登录和非教师角色的访问拦截。
 */
@SpringBootTest
@AutoConfigureMockMvc
class QuestionBankIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    /**
     * 每个测试前清空业务数据。
     *
     * <p>删除顺序必须从最下游的表开始（答案 → 答卷 → 考试 → 试卷题目 → 试卷 → 选项 → 题目 → 知识点），
     * 否则会撞上外键约束。账号表不清，它由 data.sql 提供且各测试共用。
     */
    @BeforeEach
    void clearQuestionBank() {
        jdbc.update("DELETE FROM submission_answer");
        jdbc.update("DELETE FROM submission");
        jdbc.update("DELETE FROM exam");
        jdbc.update("DELETE FROM paper_question");
        jdbc.update("DELETE FROM paper");
        jdbc.update("DELETE FROM question_option");
        jdbc.update("DELETE FROM question");
        jdbc.update("DELETE FROM knowledge_point");
    }

    /** 知识点可增改删；非创建者修改返回 403，验证资源归属校验生效。 */
    @Test
    void managesKnowledgePointsAndEnforcesOwnership() throws Exception {
        String teacher = login("teacher");
        long id = createPoint(teacher, "Java 基础");
        mockMvc.perform(get("/api/v1/knowledge-points").header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value("Java 基础"));
        mockMvc.perform(put("/api/v1/knowledge-points/{id}", id).header("Authorization", "Bearer " + login("teacher2"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"越权修改\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("RESOURCE_FORBIDDEN"));
        mockMvc.perform(put("/api/v1/knowledge-points/{id}", id).header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Java 核心\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("Java 核心"));
        mockMvc.perform(delete("/api/v1/knowledge-points/{id}", id).header("Authorization", "Bearer " + teacher))
                .andExpect(status().isNoContent());
    }

    /** 四种基础题型都能创建，且按题型、难度、知识点组合筛选时只命中预期的一条。 */
    @Test
    void createsFourTypesAndFiltersQuestions() throws Exception {
        String token = login("teacher");
        long pointId = createPoint(token, "集合");
        createQuestion(token, questionJson("SINGLE_CHOICE", "单选题", "[\"A\"]", pointId,
                "[{\"key\":\"A\",\"content\":\"正确\"},{\"key\":\"B\",\"content\":\"错误\"}]"));
        createQuestion(token, questionJson("MULTIPLE_CHOICE", "多选题", "[\"A\",\"B\"]", pointId,
                "[{\"key\":\"A\",\"content\":\"甲\"},{\"key\":\"B\",\"content\":\"乙\"}]"));
        createQuestion(token, questionJson("TRUE_FALSE", "判断题", "true", pointId, "[]"));
        createQuestion(token, questionJson("SHORT_ANSWER", "简答题", "\"参考答案\"", pointId, "[]"));

        mockMvc.perform(get("/api/v1/questions").header("Authorization", "Bearer " + token)
                        .param("type", "TRUE_FALSE").param("difficulty", "MEDIUM")
                        .param("knowledgePointId", Long.toString(pointId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].stem").value("判断题"));
    }

    /**
     * 题目可编辑；标准答案指向不存在的选项时返回 400；未被试卷引用的题目可真删。
     *
     * <p>最后一步查询返回 404 说明确实做了物理删除——如果题目已被试卷引用，
     * 这里应该变成停用并仍能查到，那条规则由组卷相关测试覆盖。
     */
    @Test
    void updatesDeletesAndRejectsInvalidQuestion() throws Exception {
        String token = login("teacher");
        long pointId = createPoint(token, "异常处理");
        long id = createQuestion(token, questionJson("TRUE_FALSE", "原题干", "true", pointId, "[]"));
        mockMvc.perform(put("/api/v1/questions/{id}", id).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionJson("TRUE_FALSE", "新题干", "false", pointId, "[]")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.stem").value("新题干"));
        mockMvc.perform(post("/api/v1/questions").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionJson("SINGLE_CHOICE", "非法单选", "[\"C\"]", pointId,
                                "[{\"key\":\"A\",\"content\":\"甲\"},{\"key\":\"B\",\"content\":\"乙\"}]")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUESTION"));
        mockMvc.perform(delete("/api/v1/questions/{id}", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/questions/{id}", id).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    }

    /** 未登录访问返回 401，学生访问教师接口返回 403，对应验收用例 8。 */
    @Test
    void rejectsUnauthenticatedAndStudentAccess() throws Exception {
        mockMvc.perform(get("/api/v1/questions")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/questions").header("Authorization", "Bearer " + login("student"))).andExpect(status().isForbidden());
    }

    /** 建一个知识点并返回其 ID。 */
    private long createPoint(String token, String name) throws Exception {
        String response = mockMvc.perform(post("/api/v1/knowledge-points").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data/id").asLong();
    }

    /** 建一道题并返回其 ID。 */
    private long createQuestion(String token, String content) throws Exception {
        String response = mockMvc.perform(post("/api/v1/questions").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data/id").asLong();
    }

    /** 拼题目请求体。手写 JSON 而不是构造对象，这样测试里能一眼看出实际发出去的报文长什么样。 */
    private String questionJson(String type, String stem, String answer, long pointId, String options) {
        return "{\"type\":\"" + type + "\",\"stem\":\"" + stem
                + "\",\"difficulty\":\"MEDIUM\",\"standardAnswer\":" + answer
                + ",\"suggestedScore\":10.0,\"knowledgePointId\":" + pointId + ",\"options\":" + options + "}";
    }

    /** 以指定账号登录并返回访问令牌，演示账号密码统一为 ExamDemo123!。 */
    private String login(String username) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"ExamDemo123!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return json.at("/data/accessToken").asText();
    }
}
