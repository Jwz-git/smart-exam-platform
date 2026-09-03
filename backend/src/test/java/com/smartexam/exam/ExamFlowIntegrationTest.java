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

@SpringBootTest
@AutoConfigureMockMvc
class ExamFlowIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExamService service;

    @BeforeEach void clear() {
        jdbc.update("DELETE FROM submission_answer"); jdbc.update("DELETE FROM submission");
        jdbc.update("DELETE FROM exam"); jdbc.update("DELETE FROM paper_question"); jdbc.update("DELETE FROM paper");
        jdbc.update("DELETE FROM question_option"); jdbc.update("DELETE FROM question"); jdbc.update("DELETE FROM knowledge_point");
    }

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

    @Test void automaticallySubmitsExpiredInProgressSubmission() throws Exception {
        String teacher = login("teacher");
        long point = id(postJson("/api/v1/knowledge-points", "{\"name\":\"超时\"}", teacher));
        long question = id(postJson("/api/v1/questions", question("TRUE_FALSE", "判断", "true", point, "[]"), teacher));
        long paper = id(postJson("/api/v1/papers", "{\"name\":\"超时卷\",\"durationMinutes\":30,\"totalScore\":10,\"questions\":[" + item(question) + "]}", teacher));
        postJson("/api/v1/papers/" + paper + "/publish", null, teacher);
        Instant now = Instant.now();
        long exam = id(postJson("/api/v1/exams", "{\"name\":\"超时考试\",\"paperId\":" + paper + ",\"startAt\":\"" + now.minusSeconds(120) + "\",\"endAt\":\"" + now.plusSeconds(600) + "\"}", teacher));
        postJson("/api/v1/exams/" + exam + "/publish", null, teacher);
        long submission = id(postJson("/api/v1/exams/" + exam + "/submissions", null, login("student")));
        jdbc.update("UPDATE exam SET end_at=? WHERE id=?", java.sql.Timestamp.from(now.minusSeconds(1)), exam);
        service.autoSubmitExpired();
        org.junit.jupiter.api.Assertions.assertEquals("SUBMITTED", jdbc.queryForObject("SELECT status FROM submission WHERE id=?", String.class, submission));
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String body, String token) throws Exception {
        var request = post(path).header("Authorization", bearer(token));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(request);
    }
    private long id(org.springframework.test.web.servlet.ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()).at("/data/id").asLong(); }
    private String item(long id) { return "{\"questionId\":" + id + ",\"score\":10}"; }
    private String question(String type, String stem, String answer, long point, String options) { return "{\"type\":\"" + type + "\",\"stem\":\"" + stem + "\",\"difficulty\":\"MEDIUM\",\"standardAnswer\":" + answer + ",\"suggestedScore\":10,\"knowledgePointId\":" + point + ",\"options\":" + options + "}"; }
    private String bearer(String token) { return "Bearer " + token; }
    private String login(String username) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"ExamDemo123!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).at("/data/accessToken").asText();
    }
}
