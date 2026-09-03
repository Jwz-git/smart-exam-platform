package com.smartexam.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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
 * 统计分析与系统设置的集成测试。
 *
 * <p>两个重点：
 * <ul>
 *   <li><b>统计口径</b>：主观题在评分前后必须给出不同的数字——评分前它不进平均分且计入「未评分」，
 *       评分后才参与统计。这一条是「平均分为什么只算已评完的答卷」的可执行证据。</li>
 *   <li><b>不泄露密钥</b>：系统设置接口返回 AI 服务商信息，因此断言响应正文里
 *       不出现测试配置里的密钥字符串，只有「是否已配置」这个布尔值。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatsIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    /** 清空业务数据，删除顺序遵循外键依赖，从最下游的表开始。 */
    @BeforeEach void clear() {
        jdbc.update("DELETE FROM submission_answer"); jdbc.update("DELETE FROM submission");
        jdbc.update("DELETE FROM exam"); jdbc.update("DELETE FROM paper_question"); jdbc.update("DELETE FROM paper");
        jdbc.update("DELETE FROM question_option"); jdbc.update("DELETE FROM question"); jdbc.update("DELETE FROM knowledge_point");
    }

    /**
     * 题库分布与教学活动概况。
     *
     * <p>四道题分别是单选、多选、判断、简答，因此题型分布必须给出五行（编程题为 0）而不是四行：
     * 数据库的 GROUP BY 只返回出现过的值，补 0 是服务端刻意做的，否则教师看不出题型覆盖的缺口。
     */
    @Test void summarizesQuestionBankDistributionAndTeachingActivity() throws Exception {
        String teacher = login("teacher");
        long exam = acceptanceExam(teacher);
        String student = login("student");
        submitAcceptancePaper(exam, student);

        JsonNode data = body(getJson("/api/v1/stats/overview", teacher).andExpect(status().isOk())).at("/data");
        // 题库：4 道题全部启用，1 个知识点。
        assertEquals(4, data.at("/bank/total").asInt());
        assertEquals(4, data.at("/bank/active").asInt());
        assertEquals(0, data.at("/bank/disabled").asInt());
        // 题型分布固定五行、固定顺序，编程题补 0。顺序必须是业务顺序（单选→多选→判断→简答→编程），
        // 不能是 Map.of 的哈希顺序——只按 key 查计数的断言发现不了顺序错乱。
        assertEquals(5, data.at("/bank/byType").size());
        assertEquals("SINGLE_CHOICE", data.at("/bank/byType/0/key").asText());
        assertEquals("MULTIPLE_CHOICE", data.at("/bank/byType/1/key").asText());
        assertEquals("TRUE_FALSE", data.at("/bank/byType/2/key").asText());
        assertEquals("SHORT_ANSWER", data.at("/bank/byType/3/key").asText());
        assertEquals("PROGRAMMING", data.at("/bank/byType/4/key").asText());
        assertEquals("EASY", data.at("/bank/byDifficulty/0/key").asText());
        assertEquals("HARD", data.at("/bank/byDifficulty/2/key").asText());
        assertEquals(1, typeCount(data, "SINGLE_CHOICE"));
        assertEquals(1, typeCount(data, "SHORT_ANSWER"));
        assertEquals(0, typeCount(data, "PROGRAMMING"));
        // 难度分布同样补 0：四道题都建成 MEDIUM。
        assertEquals(3, data.at("/bank/byDifficulty").size());
        assertEquals(4, data.at("/bank/byDifficulty/1/count").asInt());
        // 知识点分布：唯一的知识点下挂 4 道题。
        assertEquals(4, data.at("/bank/byKnowledgePoint/0/count").asInt());

        // 教学活动：1 份已发布试卷、1 场已发布考试、1 份已交答卷、1 道待批主观题。
        assertEquals(1, data.at("/activity/paperCount").asInt());
        assertEquals(1, data.at("/activity/publishedPaperCount").asInt());
        assertEquals(1, data.at("/activity/publishedExamCount").asInt());
        assertEquals(0, data.at("/activity/resultsPublishedExamCount").asInt());
        assertEquals(1, data.at("/activity/submissionCount").asInt());
        assertEquals(0, data.at("/activity/inProgressCount").asInt());
        assertEquals(1, data.at("/activity/pendingSubjectiveCount").asInt());
    }

    /**
     * 单场考试的成绩分布与逐题正确率，并验证主观题在评分前后的口径差异。
     *
     * <p>验收数据下的关键数字：客观题 20 分、简答评 8 分、总分 28 分、试卷满分 40 分，
     * 及格线 24.0 分，28/40 = 70% 落在「70—79%」这一段。
     */
    @Test void analysesScoreDistributionAndPerQuestionCorrectRate() throws Exception {
        String teacher = login("teacher");
        long exam = acceptanceExam(teacher);
        String student = login("student");
        long submission = submitAcceptancePaper(exam, student);

        // 评分前：简答题未评分，不进平均分，averageScore 为 null，ungradedCount 为 1。
        JsonNode before = body(getJson("/api/v1/stats/exams/" + exam, teacher).andExpect(status().isOk())).at("/data");
        assertEquals(1, before.at("/submissionCount").asInt());
        assertEquals(0, before.at("/gradedCount").asInt(), "还有未评的主观题，答卷不进统计");
        assertEquals(1, before.at("/questions/3/ungradedCount").asInt());
        assertTrue(before.at("/questions/3/averageScore").isNull(), "未评分的主观题不给平均分");
        // 客观题在交卷事务里已判完分，因此评分前就有正确率。
        assertEquals(100.0, before.at("/questions/0/correctRate").asDouble(), "单选答对");
        assertEquals(0.0, before.at("/questions/1/correctRate").asDouble(), "多选少选不给部分分");
        assertTrue(before.at("/questions/3/correctRate").isNull(), "主观题没有正确率");

        // 给简答题评 8 分。
        long answerId = subjectiveAnswerId(submission, teacher);
        putJson("/api/v1/submission-answers/" + answerId + "/score", "{\"score\":8,\"comment\":\"要点齐\"}", teacher)
                .andExpect(status().isOk());

        JsonNode after = body(getJson("/api/v1/stats/exams/" + exam, teacher).andExpect(status().isOk())).at("/data");
        assertEquals(40.0, after.at("/paperTotalScore").asDouble());
        assertEquals(24.0, after.at("/passScore").asDouble(), "及格线 = 40 × 60%");
        assertEquals(1, after.at("/gradedCount").asInt());
        assertEquals(28.0, after.at("/averageScore").asDouble());
        // 成绩分布：五段固定输出，28/40 = 70% 落在第三段。
        assertEquals(5, after.at("/distribution").size());
        assertEquals("70—79%", after.at("/distribution/2/label").asText());
        assertEquals(1, after.at("/distribution/2/count").asInt());
        assertEquals(100.0, after.at("/distribution/2/ratio").asDouble());
        assertEquals(0, after.at("/distribution/0/count").asInt());
        // 逐题：单选满分、多选 0 分、简答 8 分且已评完。
        assertEquals(10.0, after.at("/questions/0/averageScore").asDouble());
        assertEquals(100.0, after.at("/questions/0/scoreRate").asDouble());
        assertEquals(1, after.at("/questions/0/answeredCount").asInt());
        assertEquals(0, after.at("/questions/0/blankCount").asInt());
        assertEquals(0.0, after.at("/questions/1/averageScore").asDouble());
        assertEquals(8.0, after.at("/questions/3/averageScore").asDouble());
        assertEquals(80.0, after.at("/questions/3/scoreRate").asDouble());
        assertEquals(0, after.at("/questions/3/ungradedCount").asInt());
        assertTrue(after.at("/questions/3/subjective").asBoolean());
    }

    /** 未登录 401、学生与管理员 403、别人的考试 403、不存在的考试 404。 */
    @Test void rejectsUnauthorizedForbiddenAndMissingExam() throws Exception {
        String teacher = login("teacher");
        long exam = acceptanceExam(teacher);

        mockMvc.perform(get("/api/v1/stats/overview")).andExpect(status().isUnauthorized());
        getJson("/api/v1/stats/overview", login("student")).andExpect(status().isForbidden());
        getJson("/api/v1/stats/overview", login("admin")).andExpect(status().isForbidden());
        getJson("/api/v1/stats/exams/" + exam, login("teacher2")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RESOURCE_FORBIDDEN"));
        getJson("/api/v1/stats/exams/999999", teacher).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXAM_NOT_FOUND"));
    }

    /**
     * 系统设置返回真实生效的运行参数，且不泄露 AI 密钥。
     *
     * <p>测试配置里的密钥是 {@code test-only-key}，断言整段响应正文都不含它——
     * 这比只检查某个字段是否为空更强：新增字段时若不小心把密钥带出来，这条会立刻失败。
     */
    @Test void exposesRuntimeSettingsToTeacherAndAdminWithoutLeakingApiKey() throws Exception {
        String raw = getJson("/api/v1/system/settings", login("teacher")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.security.accessTokenMinutes").value(60))
                .andExpect(jsonPath("$.data.security.tokenRevocable").value(false))
                .andExpect(jsonPath("$.data.exam.passRatioPercent").value(60))
                .andExpect(jsonPath("$.data.ai.configured").value(true))
                .andExpect(jsonPath("$.data.ai.protocol").value("openai"))
                .andExpect(jsonPath("$.data.ai.model").value("test-model"))
                // H2 测试库不执行 Flyway，因此迁移版本必须是 null 而不是编造的版本号。
                .andExpect(jsonPath("$.data.database.schemaVersion").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertFalse(raw.contains("test-only-key"), "系统设置响应不得包含 AI 密钥");

        getJson("/api/v1/system/settings", login("admin")).andExpect(status().isOk());
        getJson("/api/v1/system/settings", login("student")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/system/settings")).andExpect(status().isUnauthorized());
    }

    /** 取题型分布里某个题型的计数。 */
    private int typeCount(JsonNode data, String type) {
        for (JsonNode item : data.at("/bank/byType")) {
            if (type.equals(item.at("/key").asText())) return item.at("/count").asInt();
        }
        return -1;
    }

    /** 取答卷里那道主观题的答案记录 ID，评分接口需要它。 */
    private long subjectiveAnswerId(long submission, String teacher) throws Exception {
        JsonNode detail = body(getJson("/api/v1/submissions/" + submission, teacher));
        for (JsonNode answer : detail.at("/data/answers")) {
            if (answer.at("/subjective").asBoolean()) return answer.at("/id").asLong();
        }
        throw new IllegalStateException("答卷里没有主观题");
    }

    /** 建出验收前置数据里的那份试卷和考试：单选、多选、判断、简答各一题，每题 10 分，总分 40。 */
    private long acceptanceExam(String teacher) throws Exception {
        long point = id(postJson("/api/v1/knowledge-points", "{\"name\":\"Java\"}", teacher));
        long single = id(postJson("/api/v1/questions", question("SINGLE_CHOICE", "单选", "[\"A\"]", point,
                "[{\"key\":\"A\",\"content\":\"对\"},{\"key\":\"B\",\"content\":\"错\"}]"), teacher));
        long multiple = id(postJson("/api/v1/questions", question("MULTIPLE_CHOICE", "多选", "[\"A\",\"B\"]", point,
                "[{\"key\":\"A\",\"content\":\"甲\"},{\"key\":\"B\",\"content\":\"乙\"}]"), teacher));
        long truth = id(postJson("/api/v1/questions", question("TRUE_FALSE", "判断", "true", point, "[]"), teacher));
        long shortAnswer = id(postJson("/api/v1/questions", question("SHORT_ANSWER", "简答", "\"参考\"", point, "[]"), teacher));
        long paper = id(postJson("/api/v1/papers", "{\"name\":\"验收卷\",\"durationMinutes\":30,\"totalScore\":40,\"questions\":["
                + item(single) + "," + item(multiple) + "," + item(truth) + "," + item(shortAnswer) + "]}", teacher));
        postJson("/api/v1/papers/" + paper + "/publish", null, teacher);
        long exam = id(postJson("/api/v1/exams", examBody("Java考试", paper), teacher));
        postJson("/api/v1/exams/" + exam + "/publish", null, teacher);
        return exam;
    }

    /** 学生按验收数据作答并交卷：单选答对、多选少选、判断答对、简答已作答，客观题应得 20 分。 */
    private long submitAcceptancePaper(long exam, String student) throws Exception {
        JsonNode started = body(postJson("/api/v1/exams/" + exam + "/submissions", null, student));
        long submission = started.at("/data/id").asLong();
        String answers = "{\"answers\":["
                + "{\"paperQuestionId\":" + paperQuestionId(started, 0) + ",\"answerContent\":[\"A\"]},"
                + "{\"paperQuestionId\":" + paperQuestionId(started, 1) + ",\"answerContent\":[\"A\"]},"
                + "{\"paperQuestionId\":" + paperQuestionId(started, 2) + ",\"answerContent\":true},"
                + "{\"paperQuestionId\":" + paperQuestionId(started, 3) + ",\"answerContent\":\"作答\"}]}";
        putJson("/api/v1/submissions/" + submission + "/answers", answers, student).andExpect(status().isOk());
        postJson("/api/v1/submissions/" + submission + "/submit", null, student)
                .andExpect(jsonPath("$.data.objectiveScore").value(20.0));
        return submission;
    }

    /** 取开始答卷响应里第 n 道题的试卷题目 ID。 */
    private long paperQuestionId(JsonNode started, int index) {
        return started.at("/data/questions/" + index + "/id").asLong();
    }

    /** 考试请求体：开放时间从一分钟前到一小时后，保证测试期间考试始终处于开放状态。 */
    private String examBody(String name, long paper) {
        Instant now = Instant.now();
        return "{\"name\":\"" + name + "\",\"paperId\":" + paper + ",\"startAt\":\"" + now.minusSeconds(60)
                + "\",\"endAt\":\"" + now.plusSeconds(3600) + "\"}";
    }

    /** 发一个带令牌的 POST；body 为 null 时不设置请求体，用于 publish 这类无参接口。 */
    private ResultActions postJson(String path, String body, String token) throws Exception {
        var request = post(path).header("Authorization", bearer(token));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(request);
    }
    /** 发一个带令牌的 PUT。 */
    private ResultActions putJson(String path, String body, String token) throws Exception {
        return mockMvc.perform(put(path).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    /** 发一个带令牌的 GET。 */
    private ResultActions getJson(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", bearer(token)));
    }
    /** 把响应体解析成 JSON 树。 */
    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
    /** 从响应里取出 data.id。 */
    private long id(ResultActions result) throws Exception { return body(result).at("/data/id").asLong(); }
    /** 组卷时的单题片段，固定 10 分，与验收前置数据一致。 */
    private String item(long id) { return "{\"questionId\":" + id + ",\"score\":10}"; }
    /** 拼题目请求体，难度统一为 MEDIUM。 */
    private String question(String type, String stem, String answer, long point, String options) {
        return "{\"type\":\"" + type + "\",\"stem\":\"" + stem + "\",\"difficulty\":\"MEDIUM\",\"standardAnswer\":"
                + answer + ",\"suggestedScore\":10,\"knowledgePointId\":" + point + ",\"options\":" + options + "}";
    }
    /** 拼 Authorization 头的值。 */
    private String bearer(String token) { return "Bearer " + token; }
    /** 以指定账号登录并返回访问令牌。 */
    private String login(String username) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"ExamDemo123!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).at("/data/accessToken").asText();
    }
}
