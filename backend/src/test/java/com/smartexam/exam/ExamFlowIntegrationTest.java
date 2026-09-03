package com.smartexam.exam;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

/**
 * 考试主流程的集成测试，对应 {@code plan.md} 验收用例 2、3、4、9。
 *
 * <p>第一个测试跑的就是验收前置数据：单选、多选、判断、简答各一题，每题 10 分，
 * 总分 40 分的试卷；学生单选答对、多选少选、判断答对、简答已作答，
 * 因此客观题应得 20 分。这个数字是验收标准里写死的，改动判分逻辑后它必须仍然成立。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExamFlowIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExamService service;
    @Autowired org.springframework.context.ApplicationContext context;

    /** 清空业务数据，删除顺序遵循外键依赖，从最下游的表开始。 */
    @BeforeEach void clear() {
        jdbc.update("DELETE FROM submission_answer"); jdbc.update("DELETE FROM submission");
        jdbc.update("DELETE FROM exam"); jdbc.update("DELETE FROM paper_question"); jdbc.update("DELETE FROM paper");
        jdbc.update("DELETE FROM question_option"); jdbc.update("DELETE FROM question"); jdbc.update("DELETE FROM knowledge_point");
    }

    /**
     * 完整走一遍「建题 → 组卷 → 发布试卷 → 创建并发布考试 → 学生答题 → 交卷 → 客观题判分」。
     *
     * <p>三处断言是验收要点：题目数为 4、客观题得分为 20 分、重复交卷返回 409 且
     * 数据库里仍只有一份答卷（唯一约束生效，没有产生重复计分）。
     */
    @Test void completesPaperExamSubmissionAndObjectiveScoring() throws Exception {
        String teacher = login("teacher");
        long point = id(postJson("/api/v1/knowledge-points", "{\"name\":\"Java\"}", teacher));
        long single = id(postJson("/api/v1/questions", question("SINGLE_CHOICE", "单选", "[\"A\"]", point,
                "[{\"key\":\"A\",\"content\":\"对\"},{\"key\":\"B\",\"content\":\"错\"}]"), teacher));
        long multiple = id(postJson("/api/v1/questions", question("MULTIPLE_CHOICE", "多选", "[\"A\",\"B\"]", point,
                "[{\"key\":\"A\",\"content\":\"甲\"},{\"key\":\"B\",\"content\":\"乙\"},{\"key\":\"C\",\"content\":\"丙\"}]"), teacher));
        long truth = id(postJson("/api/v1/questions", question("TRUE_FALSE", "判断", "true", point, "[]"), teacher));
        long shortAnswer = id(postJson("/api/v1/questions", question("SHORT_ANSWER", "简答", "\"参考\"", point, "[]"), teacher));

        String paperBody = "{\"name\":\"验收卷\",\"durationMinutes\":30,\"totalScore\":40,\"questions\":["
                + item(single) + "," + item(multiple) + "," + item(truth) + "," + item(shortAnswer) + "]}";
        long paper = id(postJson("/api/v1/papers", paperBody, teacher));
        postJson("/api/v1/papers/" + paper + "/publish", null, teacher).andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        Instant now = Instant.now();
        String examBody = "{\"name\":\"Java考试\",\"paperId\":" + paper + ",\"startAt\":\"" + now.minusSeconds(60)
                + "\",\"endAt\":\"" + now.plusSeconds(3600) + "\"}";
        long exam = id(postJson("/api/v1/exams", examBody, teacher));
        postJson("/api/v1/exams/" + exam + "/publish", null, teacher);

        String student = login("student");
        String started = postJson("/api/v1/exams/" + exam + "/submissions", null, student)
                .andExpect(jsonPath("$.data.questions.length()").value(4)).andReturn().getResponse().getContentAsString();
        JsonNode startJson = json.readTree(started);
        long submission = startJson.at("/data/id").asLong();
        long pq1 = startJson.at("/data/questions/0/id").asLong();
        long pq2 = startJson.at("/data/questions/1/id").asLong();
        long pq3 = startJson.at("/data/questions/2/id").asLong();
        long pq4 = startJson.at("/data/questions/3/id").asLong();
        String answers = "{\"answers\":[{\"paperQuestionId\":" + pq1 + ",\"answerContent\":[\"A\"]},"
                + "{\"paperQuestionId\":" + pq2 + ",\"answerContent\":[\"A\"]},"
                + "{\"paperQuestionId\":" + pq3 + ",\"answerContent\":true},"
                + "{\"paperQuestionId\":" + pq4 + ",\"answerContent\":\"作答\"}]}";
        mockMvc.perform(put("/api/v1/submissions/{id}/answers", submission).header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON).content(answers)).andExpect(status().isOk());
        postJson("/api/v1/submissions/" + submission + "/submit", null, student)
                .andExpect(jsonPath("$.data.objectiveScore").value(20.0));
        postJson("/api/v1/submissions/" + submission + "/submit", null, student)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUBMISSION_ALREADY_SUBMITTED"));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM submission WHERE exam_id=? AND student_id=3", Integer.class, exam));
    }

    /**
     * 组卷的三类失败路径：分值合计与总分不符返回 400、未登录返回 401、
     * 学生访问试卷接口返回 403、教师引用他人题目返回 403。
     */
    @Test void rejectsScoreMismatchUnauthorizedAndForeignResources() throws Exception {
        String teacher = login("teacher"); long point = id(postJson("/api/v1/knowledge-points", "{\"name\":\"权限\"}", teacher));
        long question = id(postJson("/api/v1/questions", question("TRUE_FALSE", "题目", "true", point, "[]"), teacher));
        postJson("/api/v1/papers", "{\"name\":\"错误分值\",\"durationMinutes\":30,\"totalScore\":20,\"questions\":[" + item(question) + "]}", teacher)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAPER_SCORE_MISMATCH"));
        mockMvc.perform(get("/api/v1/papers")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/papers").header("Authorization", bearer(login("student")))).andExpect(status().isForbidden());
        postJson("/api/v1/papers", "{\"name\":\"越权\",\"durationMinutes\":30,\"totalScore\":10,\"questions\":[" + item(question) + "]}", login("teacher2"))
                .andExpect(status().isForbidden());
    }

    /**
     * 验收用例 9：截止时间到达后答卷被自动提交，且学生不能继续修改答案。
     *
     * <p>做法是直接把考试结束时间改到过去，再手动触发一次定时任务，
     * 这样不必等真实的 30 秒轮询间隔。断言答卷状态变为 SUBMITTED，
     * 说明自动交卷不依赖学生浏览器保持打开。
     *
     * <p>最后再发一次保存答案：必须返回 409。少了这一条，「自动交卷」就只是改了个状态，
     * 学生仍能在截止之后把答案覆盖掉。
     */
    @Test void automaticallySubmitsExpiredInProgressSubmission() throws Exception {
        String teacher = login("teacher");
        long point = id(postJson("/api/v1/knowledge-points", "{\"name\":\"超时\"}", teacher));
        long question = id(postJson("/api/v1/questions", question("TRUE_FALSE", "判断", "true", point, "[]"), teacher));
        long paper = id(postJson("/api/v1/papers", "{\"name\":\"超时卷\",\"durationMinutes\":30,\"totalScore\":10,\"questions\":[" + item(question) + "]}", teacher));
        postJson("/api/v1/papers/" + paper + "/publish", null, teacher);
        Instant now = Instant.now();
        long exam = id(postJson("/api/v1/exams", "{\"name\":\"超时考试\",\"paperId\":" + paper + ",\"startAt\":\"" + now.minusSeconds(120) + "\",\"endAt\":\"" + now.plusSeconds(600) + "\"}", teacher));
        postJson("/api/v1/exams/" + exam + "/publish", null, teacher);
        String student = login("student");
        String started = postJson("/api/v1/exams/" + exam + "/submissions", null, student)
                .andReturn().getResponse().getContentAsString();
        JsonNode startJson = json.readTree(started);
        long submission = startJson.at("/data/id").asLong();
        long paperQuestion = startJson.at("/data/questions/0/id").asLong();
        jdbc.update("UPDATE exam SET end_at=? WHERE id=?", java.sql.Timestamp.from(now.minusSeconds(1)), exam);
        service.autoSubmitExpired();
        org.junit.jupiter.api.Assertions.assertEquals("SUBMITTED", jdbc.queryForObject("SELECT status FROM submission WHERE id=?", String.class, submission));

        // 自动交卷之后再保存答案必须被拒绝，否则截止时间形同虚设。
        mockMvc.perform(put("/api/v1/submissions/{id}/answers", submission).header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"paperQuestionId\":" + paperQuestion + ",\"answerContent\":false}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBMISSION_ALREADY_SUBMITTED"));
    }

    /**
     * 定时触发器在 Web 模式下必须注册。
     *
     * <p>上面的超时用例是直接调 {@code service.autoSubmitExpired()} 来跳过 30 秒轮询的，
     * 因此即使定时触发器被误删，那个用例照样会通过——自动交卷却已经不会发生了。
     * 这条断言专门盯住这个盲区。
     *
     * <p>{@link ExamScheduler} 刻意只在 Web 模式注册：非 Web 模式（跑 Flyway 迁移用）
     * 一旦注册定时任务，非守护的调度线程会让进程无法退出，一键初始化脚本会卡死。
     */
    @Test void registersAutoSubmitSchedulerInWebMode() {
        org.junit.jupiter.api.Assertions.assertNotNull(context.getBean(ExamScheduler.class));
    }

    /** 发一个带令牌的 POST；body 为 null 时不设置请求体，用于 publish 这类无参接口。 */
    private org.springframework.test.web.servlet.ResultActions postJson(String path, String body, String token) throws Exception {
        var request = post(path).header("Authorization", bearer(token));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(request);
    }
    /** 从响应里取出 data.id。 */
    private long id(org.springframework.test.web.servlet.ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()).at("/data/id").asLong(); }
    /** 组卷时的单题片段，固定 10 分，与验收前置数据一致。 */
    private String item(long id) { return "{\"questionId\":" + id + ",\"score\":10}"; }
    /** 拼题目请求体。 */
    private String question(String type, String stem, String answer, long point, String options) { return "{\"type\":\"" + type + "\",\"stem\":\"" + stem + "\",\"difficulty\":\"MEDIUM\",\"standardAnswer\":" + answer + ",\"suggestedScore\":10,\"knowledgePointId\":" + point + ",\"options\":" + options + "}"; }
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
