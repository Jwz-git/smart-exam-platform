package com.smartexam.exam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 规则自动组卷的集成测试。
 *
 * <p>四件事必须成立，每一件都对应一个真实会出问题的地方：
 * <ol>
 *   <li><b>方案能原样保存成试卷。</b>自动组卷只生成方案、不写库，如果生成的方案
 *       拿去 {@code POST /papers} 会被拒（分值合计不等于总分、抽到了停用题、同一题抽了两次），
 *       这个功能就是坏的；</li>
 *   <li><b>题目不够时报错而不是少给。</b>一份莫名少了 20 分的卷子比一句「题目不够」危险得多；</li>
 *   <li><b>抽题范围是本人题库里启用的题目</b>——停用题和别的教师的题都不能被抽中；</li>
 *   <li><b>规则之间不重复抽同一道题</b>，否则保存时会撞上试卷题目的唯一约束。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaperAutoComposeIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clear() {
        jdbc.update("DELETE FROM submission_answer"); jdbc.update("DELETE FROM submission");
        jdbc.update("DELETE FROM exam"); jdbc.update("DELETE FROM paper_question"); jdbc.update("DELETE FROM paper");
        jdbc.update("DELETE FROM question_option"); jdbc.update("DELETE FROM question");
        jdbc.update("DELETE FROM knowledge_point");
    }

    /**
     * 主流程：两条规则各抽 2 道，方案总分 30 分，随后原样保存成一份 30 分的试卷。
     *
     * <p>最后一步是这个用例的重点：把方案里的题目和分值直接提交给
     * {@code POST /papers}，必须一次成功。它同时证明了三件事——分值合计等于方案总分、
     * 抽中的都是可用题目、四道题互不重复。
     */
    @Test void composesByRulesAndThePlanSavesAsAPaperUnchanged() throws Exception {
        String teacher = login("teacher");
        long java = point("Java 基础", teacher);
        long db = point("数据库", teacher);
        for (int index = 0; index < 4; index++) single("单选易" + index, "EASY", java, teacher);
        for (int index = 0; index < 3; index++) single("单选难" + index, "HARD", db, teacher);

        JsonNode plan = body(autoCompose("[" + rule("SINGLE_CHOICE", "EASY", java, 2, 10)
                + "," + rule("SINGLE_CHOICE", "HARD", db, 2, 5) + "]", teacher)
                .andExpect(jsonPath("$.data.questionCount").value(4))
                .andExpect(jsonPath("$.data.totalScore").value(30.0))
                // 每条规则都回报候选池大小：池子刚好等于抽题数时，这条规则其实没有随机空间。
                .andExpect(jsonPath("$.data.rules[0].label").value("单选题 / 易 / Java 基础"))
                .andExpect(jsonPath("$.data.rules[0].poolSize").value(4))
                .andExpect(jsonPath("$.data.rules[0].subtotal").value(20.0))
                .andExpect(jsonPath("$.data.rules[1].poolSize").value(3))
                .andExpect(jsonPath("$.data.rules[1].subtotal").value(10.0))
                .andExpect(jsonPath("$.data.items[0].question.stem").exists())
                .andExpect(jsonPath("$.data.items[0].ruleIndex").value(1))
                .andExpect(jsonPath("$.data.items[3].ruleIndex").value(2)));

        // 抽中的题目互不重复：重复的话保存时会撞上 (paper_id, question_id) 唯一约束。
        Set<Long> ids = new HashSet<>();
        List<String> items = new ArrayList<>();
        for (JsonNode item : plan.at("/data/items")) {
            long questionId = item.at("/question/id").asLong();
            assertTrue(ids.add(questionId), "同一道题不能被两条规则重复抽中");
            items.add("{\"questionId\":" + questionId + ",\"score\":" + item.path("score").asDouble() + "}");
        }
        // 第一条规则抽的是「易」，第二条抽的是「难」：前两道必须都是易，说明筛选真的生效了。
        assertEquals("EASY", plan.at("/data/items/0/question/difficulty").asText());
        assertEquals("HARD", plan.at("/data/items/3/question/difficulty").asText());

        // 方案原样提交，必须一次保存成功且总分与方案一致。
        postJson("/api/v1/papers", "{\"name\":\"自动组卷试卷\",\"durationMinutes\":45,\"totalScore\":30,"
                + "\"questions\":[" + String.join(",", items) + "]}", teacher)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalScore").value(30.0))
                .andExpect(jsonPath("$.data.questions.length()").value(4));
    }

    /**
     * 题目不够、规则之间互相抢题、抽题上限：三条失败路径都必须给出可读原因。
     *
     * <p>中间那一条最容易被忽略：两条规则都指向同一批题时，第二条能用的候选已经被第一条抽走了。
     * 错误信息必须说明「已排除前面规则抽中的题目」，否则教师看着题库里明明有 4 道题却被告知只有 1 道，
     * 会以为是系统算错了。
     */
    @Test void refusesToSilentlyComposeAShortPaper() throws Exception {
        String teacher = login("teacher");
        long point = point("Java 基础", teacher);
        for (int index = 0; index < 3; index++) single("单选" + index, "EASY", point, teacher);

        // 要 5 道，只有 3 道。
        autoCompose("[" + rule("SINGLE_CHOICE", null, point, 5, 10) + "]", teacher)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTO_COMPOSE_NOT_ENOUGH"))
                .andExpect(jsonPath("$.message").value(Matchers.allOf(
                        Matchers.containsString("第 1 条规则"), Matchers.containsString("只有 3 道"))));

        // 第一条抽走 3 道，第二条就没题可抽了。
        autoCompose("[" + rule("SINGLE_CHOICE", null, point, 3, 10)
                + "," + rule(null, null, null, 1, 10) + "]", teacher)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTO_COMPOSE_NOT_ENOUGH"))
                .andExpect(jsonPath("$.message").value(Matchers.allOf(
                        Matchers.containsString("第 2 条规则（全部题目）"),
                        Matchers.containsString("已排除前面规则抽中的题目"))));

        // 超过抽题上限（系统设置默认 50 道）：先于「题目够不够」拦下来。
        autoCompose("[" + rule(null, null, null, 60, 10) + "]", teacher)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTO_COMPOSE_TOO_MANY"));

        // 知识点不存在时点名说明，而不是笼统地说题目不够。
        autoCompose("[" + rule(null, null, 999999L, 1, 10) + "]", teacher)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_POINT_NOT_FOUND"));
    }

    /**
     * 抽题范围：只抽本人题库里启用状态的题目，且参数与权限校验齐备。
     *
     * <p>停用题目本来就不允许加入新试卷，如果它出现在候选池里，抽中之后会在保存那一步才失败，
     * 教师根本不知道是哪道题的问题。
     */
    @Test void picksOnlyOwnActiveQuestionsAndChecksRoles() throws Exception {
        String teacher = login("teacher");
        long point = point("Java 基础", teacher);
        long active = single("启用题", "EASY", point, teacher);
        long disabled = single("停用题", "EASY", point, teacher);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/questions/" + disabled + "/status")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());

        // 池子里只剩一道启用题，抽 1 道必然是它；要 2 道就该失败。
        autoCompose("[" + rule(null, null, point, 1, 10) + "]", teacher)
                .andExpect(jsonPath("$.data.rules[0].poolSize").value(1))
                .andExpect(jsonPath("$.data.items[0].question.id").value(active));
        autoCompose("[" + rule(null, null, point, 2, 10) + "]", teacher)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTO_COMPOSE_NOT_ENOUGH"));

        // 另一位教师的题库是空的，同一条规则对他来说无题可抽。
        autoCompose("[" + rule(null, null, null, 1, 10) + "]", login("teacher2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTO_COMPOSE_NOT_ENOUGH"));

        // 参数校验与权限：空规则、分值非法、学生、未登录。
        autoCompose("[]", teacher).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        autoCompose("[" + rule(null, null, null, 1, 0) + "]", teacher).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        autoCompose("[" + rule(null, null, null, 1, 10) + "]", login("student")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/papers/auto-compose").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rules\":[]}")).andExpect(status().isUnauthorized());
    }

    /** 发一次自动组卷请求。 */
    private ResultActions autoCompose(String rules, String token) throws Exception {
        return postJson("/api/v1/papers/auto-compose", "{\"rules\":" + rules + "}", token);
    }

    /** 拼一条抽题规则；三个筛选维度传 null 表示不限。 */
    private String rule(String type, String difficulty, Long point, int count, double score) {
        return "{\"type\":" + quoted(type) + ",\"difficulty\":" + quoted(difficulty)
                + ",\"knowledgePointId\":" + (point == null ? "null" : point)
                + ",\"count\":" + count + ",\"score\":" + score + "}";
    }

    /** 把可空字符串写成 JSON 字面量。 */
    private String quoted(String value) { return value == null ? "null" : "\"" + value + "\""; }

    /** 新建一道两选项的单选题并返回 ID。 */
    private long single(String stem, String difficulty, long point, String token) throws Exception {
        return id(postJson("/api/v1/questions", "{\"type\":\"SINGLE_CHOICE\",\"stem\":\"" + stem
                + "\",\"difficulty\":\"" + difficulty + "\",\"standardAnswer\":[\"A\"],\"suggestedScore\":10,"
                + "\"knowledgePointId\":" + point + ",\"options\":[{\"key\":\"A\",\"content\":\"甲\"},"
                + "{\"key\":\"B\",\"content\":\"乙\"}]}", token));
    }

    /** 新建知识点并返回 ID。 */
    private long point(String name, String token) throws Exception {
        return id(postJson("/api/v1/knowledge-points", "{\"name\":\"" + name + "\"}", token));
    }

    private ResultActions postJson(String path, String body, String token) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
    private long id(ResultActions result) throws Exception { return body(result).at("/data/id").asLong(); }
    private String login(String username) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"ExamDemo123!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).at("/data/accessToken").asText();
    }
}
