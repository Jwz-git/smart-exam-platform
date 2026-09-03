package com.smartexam.ai;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * AI 辅助出题的集成测试。
 *
 * <p>{@link MockitoBean} 把 {@link AiClient} 换成桩：测试因此不联网、不消耗额度，
 * 也不会因为真实模型这次输出得好、下次输出得差而时红时绿。协议层本身由
 * {@code RestAiClientTest} 用模拟 HTTP 服务器单独验证，两者合起来覆盖整条链路。
 *
 * <p>重点验证三件事：模型输出的容错解析（围栏、前言、答案形态）、
 * 草稿必须通过与手工出题完全相同的校验、以及失败时给出可读错误而不是 500。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiDraftIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    /** 替换真实 AI 客户端的桩，由每个用例决定它返回什么文本。 */
    @MockitoBean AiClient aiClient;

    /** 每个用例都从干净题库开始，避免上一个用例保存的题目影响断言。 */
    @BeforeEach void clear() {
        jdbc.update("DELETE FROM question_option"); jdbc.update("DELETE FROM question");
        jdbc.update("DELETE FROM knowledge_point");
    }

    /**
     * 完整走一遍「生成草稿 → 教师确认 → 走题目新增接口保存」。
     *
     * <p>模型输出刻意套上 Markdown 围栏并加了一句前言：这是真实模型最常见的行为，
     * 直接 readTree 会失败。最后把草稿原样 POST 到 {@code /api/v1/questions} 是关键一步——
     * 它证明草稿的字段形状与手工出题完全一致，AI 没有走任何特殊通道。
     */
    @Test void generatesDraftThatPassesTheSameValidationAsManualCreation() throws Exception {
        String teacher = login("teacher");
        long point = id(postJson("/api/v1/knowledge-points", "{\"name\":\"面向对象\"}", teacher));
        when(aiClient.complete(anyString(), anyString())).thenReturn("""
                好的，这是生成的题目：
                ```json
                {"questions":[{"stem":"封装的主要作用是什么？","options":[
                  {"key":"a","content":"隐藏内部实现"},{"key":"B","content":"提高运行速度"},
                  {"key":"C","content":"减少内存占用"},{"key":"D","content":"简化语法"}],
                  "standardAnswer":["A"],"explanation":"封装隐藏实现细节。","tags":"Java, 面向对象"}]}
                ```
                """);

        JsonNode generated = body(postJson("/api/v1/ai/question-drafts",
                "{\"knowledgePointId\":" + point + ",\"type\":\"SINGLE_CHOICE\",\"difficulty\":\"MEDIUM\"}", teacher)
                .andExpect(jsonPath("$.data.protocol").value("openai"))
                .andExpect(jsonPath("$.data.model").value("test-model"))
                .andExpect(jsonPath("$.data.drafts.length()").value(1))
                .andExpect(jsonPath("$.data.warnings.length()").value(0))
                .andExpect(jsonPath("$.data.drafts[0].type").value("SINGLE_CHOICE"))
                .andExpect(jsonPath("$.data.drafts[0].difficulty").value("MEDIUM"))
                .andExpect(jsonPath("$.data.drafts[0].suggestedScore").value(10))
                .andExpect(jsonPath("$.data.drafts[0].knowledgePointId").value(point))
                // 小写选项键被统一成大写，否则标准答案 ["A"] 会指向一个不存在的选项。
                .andExpect(jsonPath("$.data.drafts[0].options[0].key").value("A"))
                .andExpect(jsonPath("$.data.drafts[0].standardAnswer[0]").value("A")));

        // 草稿不入库：生成之后题库里仍然是空的。
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(*) FROM question", Integer.class));

        // 教师确认后走的是普通的题目新增接口，没有任何 AI 专用保存路径。
        String draft = generated.at("/data/drafts/0").toString();
        postJson("/api/v1/questions", draft, teacher).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stem").value("封装的主要作用是什么？"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(*) FROM question", Integer.class));
    }

    /**
     * 答案形态归一化：多选题的 {@code "AB"}、判断题的 {@code "正确"} 都能被掰成合法形态。
     *
     * <p>这类输出在真实使用里很常见。归一化只改形态不改内容，正确性仍由业务校验判定；
     * 不做归一化的话，教师会看到一堆「标准答案必须是数组」的失败，而模型其实答对了。
     */
    @Test void normalizesLooseAnswerShapesFromModel() throws Exception {
        String teacher = login("teacher");
        long point = id(postJson("/api/v1/knowledge-points", "{\"name\":\"集合\"}", teacher));

        when(aiClient.complete(anyString(), anyString())).thenReturn("""
                {"questions":[{"stem":"下列哪些是 List 实现？","options":[
                  {"key":"A","content":"ArrayList"},{"key":"B","content":"LinkedList"},
                  {"key":"C","content":"HashMap"},{"key":"D","content":"HashSet"}],
                  "standardAnswer":"AB"}]}
                """);
        postJson("/api/v1/ai/question-drafts",
                "{\"knowledgePointId\":" + point + ",\"type\":\"MULTIPLE_CHOICE\",\"difficulty\":\"HARD\"}", teacher)
                .andExpect(jsonPath("$.data.drafts[0].standardAnswer[0]").value("A"))
                .andExpect(jsonPath("$.data.drafts[0].standardAnswer[1]").value("B"))
                .andExpect(jsonPath("$.data.drafts[0].difficulty").value("HARD"));

        when(aiClient.complete(anyString(), anyString()))
                .thenReturn("{\"questions\":[{\"stem\":\"ArrayList 线程安全。\",\"standardAnswer\":\"错误\"}]}");
        postJson("/api/v1/ai/question-drafts",
                "{\"knowledgePointId\":" + point + ",\"type\":\"TRUE_FALSE\",\"difficulty\":\"EASY\",\"suggestedScore\":5}", teacher)
                .andExpect(jsonPath("$.data.drafts[0].standardAnswer").value(false))
                .andExpect(jsonPath("$.data.drafts[0].suggestedScore").value(5))
                .andExpect(jsonPath("$.data.drafts[0].options.length()").value(0));

        // 简答题：模型给了对象也要落成文本，否则保存时才发现类型不对。
        when(aiClient.complete(anyString(), anyString()))
                .thenReturn("[{\"stem\":\"简述泛型的作用。\",\"standardAnswer\":\"编译期类型检查\"}]");
        postJson("/api/v1/ai/question-drafts",
                "{\"knowledgePointId\":" + point + ",\"type\":\"SHORT_ANSWER\",\"difficulty\":\"MEDIUM\"}", teacher)
                .andExpect(jsonPath("$.data.drafts[0].standardAnswer").value("编译期类型检查"));
    }

    /**
     * 部分草稿不合规时保留合规的那些，并如实告知被丢弃的原因。
     *
     * <p>第二道题只给了一个选项，通不过「选择题至少两个选项」的校验。整批作废对教师没有好处，
     * 静默丢掉更糟——他会以为模型只生成了一道。
     */
    @Test void keepsValidDraftsAndReportsRejectedOnes() throws Exception {
        String teacher = login("teacher");
        long point = id(postJson("/api/v1/knowledge-points", "{\"name\":\"异常\"}", teacher));
        when(aiClient.complete(anyString(), anyString())).thenReturn("""
                {"questions":[
                  {"stem":"合规题","options":[{"key":"A","content":"甲"},{"key":"B","content":"乙"}],"standardAnswer":["A"]},
                  {"stem":"只有一个选项","options":[{"key":"A","content":"甲"}],"standardAnswer":["A"]}]}
                """);

        postJson("/api/v1/ai/question-drafts",
                "{\"knowledgePointId\":" + point + ",\"type\":\"SINGLE_CHOICE\",\"difficulty\":\"EASY\",\"count\":2}", teacher)
                .andExpect(jsonPath("$.data.drafts.length()").value(1))
                .andExpect(jsonPath("$.data.drafts[0].stem").value("合规题"))
                .andExpect(jsonPath("$.data.warnings.length()").value(1))
                .andExpect(jsonPath("$.data.warnings[0]").value(org.hamcrest.Matchers.containsString("第 2 道")));
    }

    /**
     * 四类失败都返回可读错误而不是 500：空内容、非法 JSON、缺 questions、全部不合规。
     *
     * <p>这一组用例对应「AI 故障不能阻塞主流程」：每一种失败都只影响这个接口，
     * 前端拿到明确的 code 就能提示教师改用手工出题。
     */
    @Test void turnsModelFailuresIntoReadableErrors() throws Exception {
        String teacher = login("teacher");
        long point = id(postJson("/api/v1/knowledge-points", "{\"name\":\"IO\"}", teacher));
        String request = "{\"knowledgePointId\":" + point + ",\"type\":\"SINGLE_CHOICE\",\"difficulty\":\"EASY\"}";

        when(aiClient.complete(anyString(), anyString())).thenReturn("   ");
        postJson("/api/v1/ai/question-drafts", request, teacher)
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("AI_EMPTY_RESPONSE"));

        when(aiClient.complete(anyString(), anyString())).thenReturn("抱歉，我无法生成题目。");
        postJson("/api/v1/ai/question-drafts", request, teacher)
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("AI_INVALID_JSON"));

        when(aiClient.complete(anyString(), anyString())).thenReturn("{\"items\":[]}");
        postJson("/api/v1/ai/question-drafts", request, teacher)
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("AI_INVALID_JSON"));

        when(aiClient.complete(anyString(), anyString()))
                .thenReturn("{\"questions\":[{\"stem\":\"\",\"options\":[],\"standardAnswer\":[]}]}");
        postJson("/api/v1/ai/question-drafts", request, teacher)
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("AI_DRAFT_INVALID"));
    }

    /** 权限与参数校验：学生 403、未登录 401、数量越界和缺字段 400。 */
    @Test void enforcesTeacherRoleAndRequestValidation() throws Exception {
        String teacher = login("teacher");
        long point = id(postJson("/api/v1/knowledge-points", "{\"name\":\"权限\"}", teacher));
        String request = "{\"knowledgePointId\":" + point + ",\"type\":\"SINGLE_CHOICE\",\"difficulty\":\"EASY\"}";

        mockMvc.perform(post("/api/v1/ai/question-drafts").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized());
        postJson("/api/v1/ai/question-drafts", request, login("student")).andExpect(status().isForbidden());
        postJson("/api/v1/ai/question-drafts", request, login("admin")).andExpect(status().isForbidden());
        postJson("/api/v1/ai/question-drafts",
                "{\"knowledgePointId\":" + point + ",\"type\":\"SINGLE_CHOICE\",\"difficulty\":\"EASY\",\"count\":6}", teacher)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        postJson("/api/v1/ai/question-drafts", "{\"type\":\"SINGLE_CHOICE\",\"difficulty\":\"EASY\"}", teacher)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        postJson("/api/v1/ai/question-drafts",
                "{\"knowledgePointId\":" + point + ",\"type\":\"ESSAY\",\"difficulty\":\"EASY\"}", teacher)
                .andExpect(status().isBadRequest());
    }

    /** 发一个带令牌的 POST。 */
    private ResultActions postJson(String path, String body, String token) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    /** 把响应体解析成 JSON 树。 */
    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
    /** 从响应里取出 data.id。 */
    private long id(ResultActions result) throws Exception { return body(result).at("/data/id").asLong(); }
    /** 以指定账号登录并返回访问令牌。 */
    private String login(String username) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"ExamDemo123!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).at("/data/accessToken").asText();
    }
}
