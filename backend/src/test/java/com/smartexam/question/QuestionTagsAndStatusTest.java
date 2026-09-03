package com.smartexam.question;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** 覆盖编程题题型、关键词标签、启用/停用切换和非法分页参数。 */
@SpringBootTest
@AutoConfigureMockMvc
class QuestionTagsAndStatusTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    /** 清空业务数据，删除顺序遵循外键依赖。 */
    @BeforeEach void clear() {
        jdbc.update("DELETE FROM submission_answer"); jdbc.update("DELETE FROM submission");
        jdbc.update("DELETE FROM exam"); jdbc.update("DELETE FROM paper_question"); jdbc.update("DELETE FROM paper");
        jdbc.update("DELETE FROM question_option"); jdbc.update("DELETE FROM question"); jdbc.update("DELETE FROM knowledge_point");
    }

    /**
     * 编程题可以保存，且标签会被规范化。
     *
     * <p>输入的标签故意写得很脏（前后空格、中文逗号、结尾多一个分隔符），
     * 期望输出统一成 {@code 流程控制, Java}，这样题库列表里的标签展示才一致。
     *
     * <p>第二段验证编程题是主观题：带选项提交应被拒绝。
     */
    @Test void savesProgrammingQuestionAndNormalizesTags() throws Exception {
        String teacher = login("teacher");
        long point = createPoint(teacher, "流程控制");
        mockMvc.perform(post("/api/v1/questions").header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PROGRAMMING\",\"stem\":\"写出输出结果\",\"difficulty\":\"HARD\","
                                + "\"tags\":\" 流程控制 ，Java , \",\"standardAnswer\":\"逐轮分析\","
                                + "\"suggestedScore\":10,\"knowledgePointId\":" + point + ",\"options\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("PROGRAMMING"))
                .andExpect(jsonPath("$.data.tags").value("流程控制, Java"));

        // 编程题必须是无选项的主观题，带选项应被拒绝。
        mockMvc.perform(post("/api/v1/questions").header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PROGRAMMING\",\"stem\":\"非法编程题\",\"difficulty\":\"HARD\","
                                + "\"standardAnswer\":\"参考\",\"suggestedScore\":10,\"knowledgePointId\":" + point
                                + ",\"options\":[{\"key\":\"A\",\"content\":\"甲\"},{\"key\":\"B\",\"content\":\"乙\"}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUESTION"));
    }

    /**
     * 关键词能命中标签（而不只是题干），启用停用可切换，且越权切换返回 403。
     *
     * <p>中间两条断言验证一个刻意的设计：题库列表默认返回全部状态的题目
     * （界面要显示状态并提供启用入口），只有显式传 status 才过滤。
     */
    @Test void filtersByTagKeywordAndStatus() throws Exception {
        String teacher = login("teacher");
        long point = createPoint(teacher, "集合");
        long tagged = createQuestion(teacher, point, "集合相关题目", "List, Set");
        createQuestion(teacher, point, "无关题目", "IO");

        mockMvc.perform(get("/api/v1/questions").header("Authorization", "Bearer " + teacher).param("keyword", "Set"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].stem").value("集合相关题目"));

        mockMvc.perform(patch("/api/v1/questions/{id}/status", tagged).header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISABLED"));

        // 停用题目仍出现在列表里（界面需要显示状态），但可以按状态过滤。
        mockMvc.perform(get("/api/v1/questions").header("Authorization", "Bearer " + teacher))
                .andExpect(jsonPath("$.data.total").value(2));
        mockMvc.perform(get("/api/v1/questions").header("Authorization", "Bearer " + teacher).param("status", "ACTIVE"))
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(patch("/api/v1/questions/{id}/status", tagged).header("Authorization", "Bearer " + login("teacher2"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("RESOURCE_FORBIDDEN"));
    }

    /**
     * 非法分页参数和非法枚举值都返回 400，而不是 500。
     *
     * <p>这条测试是为了防止回归：早先 {@code page=0} 会抛出未处理的
     * ConstraintViolationException 直接变成 500，与 {@code docs/api.md} 的约定不符，
     * 也不满足验收用例 8「非法参数返回 400 并包含可读错误信息」。
     */
    @Test void rejectsIllegalPagingWithBadRequest() throws Exception {
        String teacher = login("teacher");
        mockMvc.perform(get("/api/v1/questions").header("Authorization", "Bearer " + teacher).param("page", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/v1/questions").header("Authorization", "Bearer " + teacher).param("size", "500"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/v1/questions").header("Authorization", "Bearer " + teacher).param("type", "NOT_A_TYPE"))
                .andExpect(status().isBadRequest());
    }

    /** 建一个知识点并返回其 ID。 */
    private long createPoint(String token, String name) throws Exception {
        String response = mockMvc.perform(post("/api/v1/knowledge-points").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).at("/data/id").asLong();
    }

    /** 建一道带标签的判断题并返回其 ID。 */
    private long createQuestion(String token, long point, String stem, String tags) throws Exception {
        String response = mockMvc.perform(post("/api/v1/questions").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"TRUE_FALSE\",\"stem\":\"" + stem + "\",\"difficulty\":\"EASY\",\"tags\":\""
                                + tags + "\",\"standardAnswer\":true,\"suggestedScore\":2,\"knowledgePointId\":" + point
                                + ",\"options\":[]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).at("/data/id").asLong();
    }

    /** 以指定账号登录并返回访问令牌。 */
    private String login(String username) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"ExamDemo123!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).at("/data/accessToken").asText();
    }
}
