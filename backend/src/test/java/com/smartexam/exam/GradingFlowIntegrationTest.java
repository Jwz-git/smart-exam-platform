package com.smartexam.exam;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 阅卷、成绩、排名与隐私隔离的集成测试，对应 {@code plan.md} 验收用例 5、6、7、8。
 *
 * <p>第一个测试跑的是验收前置数据：四题各 10 分、总分 40 分，学生甲客观题得 20 分，
 * 简答题评 8 分后总分必须是 28 分。这两个数字写在验收标准里，改动判分或汇总逻辑后它们必须仍然成立。
 *
 * <p>第二个测试专门构造 20、10、10、0 四个分数，用来验证同分并列的竞赛排名是
 * {@code 1、2、2、4} 而不是 {@code 1、2、2、3}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GradingFlowIntegrationTest {
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
     * 验收用例 5：教师查看完整答卷、给简答题评 8 分、公布成绩，学生随后看到 28 分并能回看本人答卷。
     *
     * <p>其中最关键的一段是「公布前的学生视图」：分数、标准答案、解析、评语和名次必须全部为 null。
     * 教师同一时刻访问同一份答卷却能看到全部字段，说明裁剪是按角色做的，而不是靠前端不显示。
     */
    @Test void gradesSubjectiveAnswerThenPublishesResultsWithStudentIsolation() throws Exception {
        String teacher = login("teacher");
        long exam = acceptanceExam(teacher);
        String student = login("student");
        long submission = answerAcceptancePaper(exam, student);
        postJson("/api/v1/submissions/" + submission + "/submit", null, student)
                .andExpect(jsonPath("$.data.objectiveScore").value(20.0));

        // 阅卷面板：一份答卷、一道主观题待批，仍在作答人数为 0。
        getJson("/api/v1/exams/" + exam + "/grading", teacher)
                .andExpect(jsonPath("$.data.submissionCount").value(1))
                .andExpect(jsonPath("$.data.pendingCount").value(1))
                .andExpect(jsonPath("$.data.inProgressCount").value(0))
                .andExpect(jsonPath("$.data.items[0].subjectiveCount").value(1))
                .andExpect(jsonPath("$.data.items[0].studentName").value("演示学生"));

        // 公布前的学生视图：只剩自己的作答，其余一律为 null。
        getJson("/api/v1/submissions/" + submission, student)
                .andExpect(jsonPath("$.data.resultsPublished").value(false))
                .andExpect(jsonPath("$.data.objectiveScore").doesNotExist())
                .andExpect(jsonPath("$.data.totalScore").doesNotExist())
                .andExpect(jsonPath("$.data.rank").doesNotExist())
                .andExpect(jsonPath("$.data.answers[0].standardAnswer").doesNotExist())
                .andExpect(jsonPath("$.data.answers[0].score").doesNotExist())
                .andExpect(jsonPath("$.data.answers[3].gradingComment").doesNotExist())
                .andExpect(jsonPath("$.data.answers[0].answerContent[0]").value("A"));

        // 教师视图：同一份答卷可见标准答案、逐题得分与主观题标记。
        JsonNode detail = body(getJson("/api/v1/submissions/" + submission, teacher)
                .andExpect(jsonPath("$.data.totalScore").value(20.0))
                .andExpect(jsonPath("$.data.answers[0].standardAnswer[0]").value("A"))
                .andExpect(jsonPath("$.data.answers[1].score").value(0.0))
                .andExpect(jsonPath("$.data.answers[3].subjective").value(true)));
        long objectiveAnswer = detail.at("/data/answers/0/id").asLong();
        long subjectiveAnswer = detail.at("/data/answers/3/id").asLong();

        // 超过该题满分、评分他人考试、给客观题人工评分：三条都必须被拒绝。
        putJson("/api/v1/submission-answers/" + subjectiveAnswer + "/score", "{\"score\":11}", teacher)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SCORE_EXCEEDS_MAX"));
        putJson("/api/v1/submission-answers/" + subjectiveAnswer + "/score", "{\"score\":8}", login("teacher2"))
                .andExpect(status().isForbidden());
        putJson("/api/v1/submission-answers/" + objectiveAnswer + "/score", "{\"score\":1}", teacher)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_SUBJECTIVE"));

        // 评 8 分：20 + 8 = 28，且该答卷状态推进为 GRADED。
        putJson("/api/v1/submission-answers/" + subjectiveAnswer + "/score",
                        "{\"score\":8,\"comment\":\"思路正确，缺少异常处理\"}", teacher)
                .andExpect(jsonPath("$.data.subjectiveScore").value(8.0))
                .andExpect(jsonPath("$.data.totalScore").value(28.0))
                .andExpect(jsonPath("$.data.answers[3].gradingComment").value("思路正确，缺少异常处理"));
        assertEquals("GRADED", jdbc.queryForObject("SELECT status FROM submission WHERE id=?", String.class, submission));
        getJson("/api/v1/exams/" + exam + "/grading", teacher).andExpect(jsonPath("$.data.pendingCount").value(0));

        // 公布成绩：统计与排名同时可用。
        postJson("/api/v1/exams/" + exam + "/publish-results", null, teacher)
                .andExpect(jsonPath("$.data.resultsPublished").value(true))
                .andExpect(jsonPath("$.data.gradedCount").value(1))
                .andExpect(jsonPath("$.data.averageScore").value(28.0))
                .andExpect(jsonPath("$.data.highestScore").value(28.0))
                .andExpect(jsonPath("$.data.lowestScore").value(28.0))
                .andExpect(jsonPath("$.data.rankings[0].rank").value(1));
        postJson("/api/v1/exams/" + exam + "/publish-results", null, teacher)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        // 公布后的学生视图：本人总分、标准答案、评语和名次都可见。
        getJson("/api/v1/my/results", student)
                .andExpect(jsonPath("$.data[0].totalScore").value(28.0))
                .andExpect(jsonPath("$.data[0].rank").value(1))
                .andExpect(jsonPath("$.data[0].totalCount").value(1));
        getJson("/api/v1/my/results/" + submission, student)
                .andExpect(jsonPath("$.data.totalScore").value(28.0))
                .andExpect(jsonPath("$.data.rank").value(1))
                .andExpect(jsonPath("$.data.answers[3].gradingComment").value("思路正确，缺少异常处理"))
                .andExpect(jsonPath("$.data.answers[0].standardAnswer[0]").value("A"));
    }

    /**
     * 验收用例 6、7：竞赛排名为 {@code 1、2、2、4}，统计正确，学生只能看到本人名次。
     *
     * <p>试卷刻意只放两道客观题（各 10 分）：四名学生分别答对两题、只答对第一题、
     * 只答对第二题、完全不答，得到 20、10、10、0 四个分数，恰好构造出一对同分并列。
     * 全客观题也意味着交卷即出分，不需要教师批阅就能验证排名规则。
     */
    @Test void ranksTiedTotalsAsCompetitionRankingAndHidesOtherStudents() throws Exception {
        String teacher = login("teacher");
        long point = id(postJson("/api/v1/knowledge-points", "{\"name\":\"排名\"}", teacher));
        long single = id(postJson("/api/v1/questions", question("SINGLE_CHOICE", "单选", "[\"A\"]", point,
                "[{\"key\":\"A\",\"content\":\"对\"},{\"key\":\"B\",\"content\":\"错\"}]"), teacher));
        long truth = id(postJson("/api/v1/questions", question("TRUE_FALSE", "判断", "true", point, "[]"), teacher));
        long paper = id(postJson("/api/v1/papers", "{\"name\":\"排名卷\",\"durationMinutes\":30,\"totalScore\":20,"
                + "\"questions\":[" + item(single) + "," + item(truth) + "]}", teacher));
        postJson("/api/v1/papers/" + paper + "/publish", null, teacher);
        long exam = id(postJson("/api/v1/exams", examBody("排名考试", paper), teacher));
        postJson("/api/v1/exams/" + exam + "/publish", null, teacher);

        long best = submitWith(exam, login("student"), "[\"A\"]", "true");
        long second = submitWith(exam, login("student2"), "[\"A\"]", "false");
        submitWith(exam, login("student3"), "[\"B\"]", "true");
        submitWith(exam, login("student4"), null, null);

        // 20、10、10、0 → 名次 1、2、2、4；平均 10 分，及格线 12 分只有一人达到。
        getJson("/api/v1/exams/" + exam + "/results", teacher)
                .andExpect(jsonPath("$.data.gradedCount").value(4))
                .andExpect(jsonPath("$.data.averageScore").value(10.0))
                .andExpect(jsonPath("$.data.highestScore").value(20.0))
                .andExpect(jsonPath("$.data.lowestScore").value(0.0))
                .andExpect(jsonPath("$.data.passRate").value(25.0))
                .andExpect(jsonPath("$.data.rankings[0].rank").value(1))
                .andExpect(jsonPath("$.data.rankings[1].rank").value(2))
                .andExpect(jsonPath("$.data.rankings[2].rank").value(2))
                .andExpect(jsonPath("$.data.rankings[3].rank").value(4))
                .andExpect(jsonPath("$.data.rankings[3].totalScore").value(0.0));

        postJson("/api/v1/exams/" + exam + "/publish-results", null, teacher).andExpect(status().isOk());

        // 学生乙只拿到本人名次和总人数，且读不到学生甲的答卷。
        String other = login("student2");
        getJson("/api/v1/my/results", other)
                .andExpect(jsonPath("$.data[0].rank").value(2))
                .andExpect(jsonPath("$.data[0].totalCount").value(4))
                .andExpect(jsonPath("$.data[0].totalScore").value(10.0));
        getJson("/api/v1/submissions/" + best, other).andExpect(status().isForbidden());
        getJson("/api/v1/my/results/" + best, other).andExpect(status().isForbidden());
        assertEquals(second, id(getJson("/api/v1/my/results/" + second, other)));
    }

    /**
     * 验收用例 8 在阅卷成绩模块上的部分：未登录 401、越权 403、状态不满足 400。
     *
     * <p>公布成绩的两条前置条件分别验证：先造出「还有人在作答」，再造出「主观题没批完」，
     * 两种情况都必须拒绝，否则半成品答卷会被算进平均分和排名。
     */
    @Test void rejectsUnauthorizedForbiddenAndPrematurePublish() throws Exception {
        String teacher = login("teacher");
        long exam = acceptanceExam(teacher);
        String student = login("student");
        long submission = answerAcceptancePaper(exam, student);

        // 仍在作答：学生乙已开始但未交卷。
        postJson("/api/v1/exams/" + exam + "/submissions", null, login("student2"));
        postJson("/api/v1/exams/" + exam + "/publish-results", null, teacher)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SUBMISSION_IN_PROGRESS"));

        // 全部交卷但简答题未批：仍然不能公布。
        postJson("/api/v1/submissions/" + submission + "/submit", null, student);
        long other = id(postJson("/api/v1/exams/" + exam + "/submissions", null, login("student2")));
        postJson("/api/v1/submissions/" + other + "/submit", null, login("student2"));
        postJson("/api/v1/exams/" + exam + "/publish-results", null, teacher)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GRADING_NOT_FINISHED"));

        // 未登录 401；学生访问阅卷面板、教师访问他人考试都是 403。
        mockMvc.perform(get("/api/v1/exams/{id}/results", exam)).andExpect(status().isUnauthorized());
        getJson("/api/v1/exams/" + exam + "/grading", student).andExpect(status().isForbidden());
        getJson("/api/v1/exams/" + exam + "/grading", login("teacher2")).andExpect(status().isForbidden());
        getJson("/api/v1/my/results", teacher).andExpect(status().isForbidden());
        getJson("/api/v1/exams/" + exam + "/grading", login("admin")).andExpect(status().isForbidden());
    }

    /** 建出验收前置数据里的那份试卷和考试：单选、多选、判断、简答各一题，每题 10 分，总分 40。 */
    private long acceptanceExam(String teacher) throws Exception {
        long point = id(postJson("/api/v1/knowledge-points", "{\"name\":\"Java\"}", teacher));
        long single = id(postJson("/api/v1/questions", question("SINGLE_CHOICE", "单选", "[\"A\"]", point,
                "[{\"key\":\"A\",\"content\":\"对\"},{\"key\":\"B\",\"content\":\"错\"}]"), teacher));
        long multiple = id(postJson("/api/v1/questions", question("MULTIPLE_CHOICE", "多选", "[\"A\",\"B\"]", point,
                "[{\"key\":\"A\",\"content\":\"甲\"},{\"key\":\"B\",\"content\":\"乙\"},{\"key\":\"C\",\"content\":\"丙\"}]"), teacher));
        long truth = id(postJson("/api/v1/questions", question("TRUE_FALSE", "判断", "true", point, "[]"), teacher));
        long shortAnswer = id(postJson("/api/v1/questions", question("SHORT_ANSWER", "简答", "\"参考\"", point, "[]"), teacher));
        long paper = id(postJson("/api/v1/papers", "{\"name\":\"验收卷\",\"durationMinutes\":30,\"totalScore\":40,\"questions\":["
                + item(single) + "," + item(multiple) + "," + item(truth) + "," + item(shortAnswer) + "]}", teacher));
        postJson("/api/v1/papers/" + paper + "/publish", null, teacher);
        long exam = id(postJson("/api/v1/exams", examBody("Java考试", paper), teacher));
        postJson("/api/v1/exams/" + exam + "/publish", null, teacher);
        return exam;
    }

    /**
     * 学生甲按验收数据作答但不交卷：单选答对、多选少选、判断答对、简答已作答。
     *
     * <p>客观题因此应得 20 分（10 + 0 + 10），简答题等教师评分。
     */
    private long answerAcceptancePaper(long exam, String student) throws Exception {
        JsonNode started = body(postJson("/api/v1/exams/" + exam + "/submissions", null, student));
        long submission = started.at("/data/id").asLong();
        String answers = "{\"answers\":["
                + "{\"paperQuestionId\":" + questionId(started, 0) + ",\"answerContent\":[\"A\"]},"
                + "{\"paperQuestionId\":" + questionId(started, 1) + ",\"answerContent\":[\"A\"]},"
                + "{\"paperQuestionId\":" + questionId(started, 2) + ",\"answerContent\":true},"
                + "{\"paperQuestionId\":" + questionId(started, 3) + ",\"answerContent\":\"作答\"}]}";
        putJson("/api/v1/submissions/" + submission + "/answers", answers, student).andExpect(status().isOk());
        return submission;
    }

    /** 开始作答、按给定答案作答（传 null 表示整卷不答）并交卷，返回答卷 ID。 */
    private long submitWith(long exam, String student, String first, String second) throws Exception {
        JsonNode started = body(postJson("/api/v1/exams/" + exam + "/submissions", null, student));
        long submission = started.at("/data/id").asLong();
        if (first != null) {
            putJson("/api/v1/submissions/" + submission + "/answers", "{\"answers\":["
                    + "{\"paperQuestionId\":" + questionId(started, 0) + ",\"answerContent\":" + first + "},"
                    + "{\"paperQuestionId\":" + questionId(started, 1) + ",\"answerContent\":" + second + "}]}", student)
                    .andExpect(status().isOk());
        }
        postJson("/api/v1/submissions/" + submission + "/submit", null, student).andExpect(status().isOk());
        return submission;
    }

    /** 取开始答卷响应里第 n 道题的试卷题目 ID。 */
    private long questionId(JsonNode started, int index) { return started.at("/data/questions/" + index + "/id").asLong(); }

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
    /** 拼题目请求体。 */
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
