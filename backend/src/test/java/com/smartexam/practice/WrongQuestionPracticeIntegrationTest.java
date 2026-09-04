package com.smartexam.practice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
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
 * 错题本与错题重练的集成测试。
 *
 * <p>四条硬约束逐一验证，每一条都对应一个真实的坏结果：
 * <ol>
 *   <li><b>成绩公布前错题本必须是空的。</b>否则「你这道错了」本身就泄露了成绩，
 *       把 {@code GradingService} 精心做的字段裁剪整个绕开；</li>
 *   <li><b>练习集不下发标准答案。</b>否则重练就是抄一遍答案；</li>
 *   <li><b>重练不改动成绩。</b>练完之后答卷的分数、状态必须一个字节都没变；</li>
 *   <li><b>只能练本人已公布考试里的错题。</b>拿一个别的题目 ID 去提交，必须换不到答案。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class WrongQuestionPracticeIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clear() {
        jdbc.update("DELETE FROM practice_attempt");
        jdbc.update("DELETE FROM submission_answer"); jdbc.update("DELETE FROM submission");
        jdbc.update("DELETE FROM exam"); jdbc.update("DELETE FROM paper_question"); jdbc.update("DELETE FROM paper");
        jdbc.update("DELETE FROM question_option"); jdbc.update("DELETE FROM question");
        jdbc.update("DELETE FROM knowledge_point");
    }

    /**
     * 主流程：公布前错题本为空 → 公布后收录 2 道 → 练对一道 → 标记已掌握，而成绩分毫未动。
     *
     * <p>用的就是验收前置数据那张卷子：客观题得 20 分（多选少选得 0），简答题评 8 分，总分 28。
     * 因此错题本里应当正好两道——0 分的多选和只得 8 分的简答。后者说明「未得满分」而不是
     * 「得 0 分」才是收录标准：一道 10 分的简答只拿 8 分，同样值得回看。
     */
    @Test void collectsWrongQuestionsOnlyAfterPublishAndPracticeDoesNotTouchScores() throws Exception {
        String teacher = login("teacher");
        long exam = acceptanceExam(teacher);
        String student = login("student");
        long submission = answerAcceptancePaper(exam, student);
        postJson("/api/v1/submissions/" + submission + "/submit", null, student)
                .andExpect(jsonPath("$.data.objectiveScore").value(20.0));

        // 公布之前：一道都不给。这时学生连自己的分数都还看不到。
        getJson("/api/v1/my/wrong-questions", student)
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
        getJson("/api/v1/my/wrong-questions/practice", student)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRACTICE_NO_QUESTION"));

        // 评简答 8 分并公布成绩。
        long subjectiveAnswer = body(getJson("/api/v1/submissions/" + submission, teacher))
                .at("/data/answers/3/id").asLong();
        putJson("/api/v1/submission-answers/" + subjectiveAnswer + "/score",
                "{\"score\":8,\"comment\":\"缺少异常处理\"}", teacher).andExpect(status().isOk());
        postJson("/api/v1/exams/" + exam + "/publish-results", null, teacher).andExpect(status().isOk());

        // 公布之后：多选（0/10，客观题）与简答（8/10，主观题）各一道。
        JsonNode book = body(getJson("/api/v1/my/wrong-questions", student)
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.objectiveCount").value(1))
                .andExpect(jsonPath("$.data.subjectiveCount").value(1))
                .andExpect(jsonPath("$.data.masteredCount").value(0))
                .andExpect(jsonPath("$.data.practiceBatchSize").value(10)));
        JsonNode multiple = book.at("/data/items/0");
        assertEquals("MULTIPLE_CHOICE", multiple.path("type").asText());
        assertEquals(0.0, multiple.path("score").asDouble(), "多选少选得 0 分");
        assertEquals(10.0, multiple.path("maxScore").asDouble());
        assertEquals("A", multiple.at("/standardAnswer/0").asText(), "复习时可以看到标准答案");
        assertEquals("A", multiple.at("/myAnswer/0").asText(), "也能看到自己当时怎么答的");
        assertEquals(0, multiple.path("practiceCount").asInt());
        JsonNode subjective = book.at("/data/items/1");
        assertEquals(true, subjective.path("subjective").asBoolean(), "简答题只能复习，不能重练");
        assertEquals(8.0, subjective.path("score").asDouble(), "未得满分即收录，不只收 0 分的题");
        assertEquals("缺少异常处理", subjective.path("gradingComment").asText());

        long paperQuestionId = multiple.path("paperQuestionId").asLong();

        // 练习集：只有那道多选，且不含标准答案、解析和上次的错误作答。
        getJson("/api/v1/my/wrong-questions/practice", student)
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.questions[0].paperQuestionId").value(paperQuestionId))
                .andExpect(jsonPath("$.data.questions[0].options[0].key").value("A"))
                .andExpect(jsonPath("$.data.questions[0].standardAnswer").doesNotExist())
                .andExpect(jsonPath("$.data.questions[0].explanation").doesNotExist())
                .andExpect(jsonPath("$.data.questions[0].myAnswer").doesNotExist());

        // 先练错一次：判错，并且此刻才返回标准答案。
        postJson("/api/v1/my/wrong-questions/practice", practiceBody(paperQuestionId, "[\"A\"]"), student)
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.correctCount").value(0))
                .andExpect(jsonPath("$.data.accuracy").value(0.0))
                .andExpect(jsonPath("$.data.answers[0].correct").value(false))
                .andExpect(jsonPath("$.data.answers[0].standardAnswer[1]").value("B"));

        // 再练对一次：多选必须集合完全一致才算对，这与交卷时的判分规则是同一份实现。
        postJson("/api/v1/my/wrong-questions/practice", practiceBody(paperQuestionId, "[\"B\",\"A\"]"), student)
                .andExpect(jsonPath("$.data.correctCount").value(1))
                .andExpect(jsonPath("$.data.accuracy").value(100.0))
                .andExpect(jsonPath("$.data.answers[0].correct").value(true));

        // 错题本记下练了两次、最近一次正确，因此标记为已掌握。
        getJson("/api/v1/my/wrong-questions", student)
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.masteredCount").value(1))
                .andExpect(jsonPath("$.data.items[0].practiceCount").value(2))
                .andExpect(jsonPath("$.data.items[0].lastCorrect").value(true))
                .andExpect(jsonPath("$.data.items[0].mastered").value(true));
        // 默认只练没练对的题，因此已掌握之后练习集就空了；显式要求「全部」仍然能练。
        getJson("/api/v1/my/wrong-questions/practice", student).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRACTICE_NO_QUESTION"));
        getJson("/api/v1/my/wrong-questions/practice?onlyUnmastered=false", student)
                .andExpect(jsonPath("$.data.size").value(1));

        // 最重要的一条：练了三次，成绩一个字节都没变。
        assertEquals(new BigDecimal("28.0"), jdbc.queryForObject(
                "SELECT total_score FROM submission WHERE id=?", BigDecimal.class, submission));
        assertEquals(new BigDecimal("20.0"), jdbc.queryForObject(
                "SELECT objective_score FROM submission WHERE id=?", BigDecimal.class, submission));
        assertEquals("GRADED", jdbc.queryForObject(
                "SELECT status FROM submission WHERE id=?", String.class, submission));
        assertEquals(0.0, jdbc.queryForObject("SELECT score FROM submission_answer WHERE submission_id=?"
                + " AND paper_question_id=?", BigDecimal.class, submission, paperQuestionId).doubleValue(),
                "重练答对了，但考试那一题的得分仍然是 0");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM practice_attempt", Integer.class),
                "练习记录写在独立的表里");
    }

    /**
     * 越权与参数校验：拿别的题目 ID 换不到答案。
     *
     * <p>第一条是本用例的重点——同一场考试还没公布成绩时，学生不能通过重练接口
     * 提前拿到那道题的标准答案。这条路径如果放开，成绩公布这道闸门就等于形同虚设。
     */
    @Test void refusesQuestionsOutsideOwnPublishedWrongBook() throws Exception {
        String teacher = login("teacher");
        long published = acceptanceExam(teacher);
        String student = login("student");
        long submission = answerAcceptancePaper(published, student);
        postJson("/api/v1/submissions/" + submission + "/submit", null, student).andExpect(status().isOk());
        long subjectiveAnswer = body(getJson("/api/v1/submissions/" + submission, teacher))
                .at("/data/answers/3/id").asLong();
        putJson("/api/v1/submission-answers/" + subjectiveAnswer + "/score", "{\"score\":8}", teacher);
        postJson("/api/v1/exams/" + published + "/publish-results", null, teacher).andExpect(status().isOk());

        JsonNode book = body(getJson("/api/v1/my/wrong-questions", student));
        long wrongMultiple = book.at("/data/items/0/paperQuestionId").asLong();
        long subjectiveQuestion = book.at("/data/items/1/paperQuestionId").asLong();

        // 主观题没有确定性判分规则，只能复习。
        postJson("/api/v1/my/wrong-questions/practice", practiceBody(subjectiveQuestion, "\"再答一次\""), student)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRACTICE_NOT_OBJECTIVE"));

        // 答对过的题不在错题本里，练它同样返回 404（错题本才是唯一的入口）。
        long correctSingle = body(getJson("/api/v1/my/results/" + submission, student))
                .at("/data/answers/0/paperQuestionId").asLong();
        postJson("/api/v1/my/wrong-questions/practice", practiceBody(correctSingle, "[\"A\"]"), student)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WRONG_QUESTION_NOT_FOUND"));

        // 另一场考试没公布成绩：即使答错了，也不能通过重练接口提前换到标准答案。
        long pending = acceptanceExam(teacher);
        long pendingSubmission = answerAcceptancePaper(pending, student);
        postJson("/api/v1/submissions/" + pendingSubmission + "/submit", null, student).andExpect(status().isOk());
        long pendingMultiple = body(getJson("/api/v1/submissions/" + pendingSubmission, teacher))
                .at("/data/answers/1/paperQuestionId").asLong();
        postJson("/api/v1/my/wrong-questions/practice", practiceBody(pendingMultiple, "[\"A\",\"B\"]"), student)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WRONG_QUESTION_NOT_FOUND"));

        // 别的学生的错题本与本人无关：学生乙没交过卷，错题本是空的。
        getJson("/api/v1/my/wrong-questions", login("student2"))
                .andExpect(jsonPath("$.data.total").value(0));

        // 同一道题重复提交、空提交，都是 400。
        postJson("/api/v1/my/wrong-questions/practice", "{\"answers\":["
                + "{\"paperQuestionId\":" + wrongMultiple + ",\"answerContent\":[\"A\"]},"
                + "{\"paperQuestionId\":" + wrongMultiple + ",\"answerContent\":[\"B\"]}]}", student)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ANSWER"));
        postJson("/api/v1/my/wrong-questions/practice", "{\"answers\":[]}", student)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        // 重复提交那一批整体失败，因此一条练习记录都不该留下。
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM practice_attempt", Integer.class));

        // 角色：教师和管理员访问 /my/** 一律 403，未登录 401。
        getJson("/api/v1/my/wrong-questions", teacher).andExpect(status().isForbidden());
        getJson("/api/v1/my/wrong-questions", login("admin")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/my/wrong-questions")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/my/wrong-questions/practice").contentType(MediaType.APPLICATION_JSON)
                .content(practiceBody(wrongMultiple, "[\"A\"]"))).andExpect(status().isUnauthorized());
    }

    /**
     * 未作答的题目同样进错题本，并且能被重练。
     *
     * <p>「没答」和「答错」在成绩上都是 0 分，但在错题本里要能区分：前者是时间不够或漏题，
     * 后者是真的不会。界面上分别显示「未作答」和自己的错误答案，复习时的意义完全不同。
     */
    @Test void treatsBlankAnswersAsWrongQuestionsToo() throws Exception {
        String teacher = login("teacher");
        long point = id(postJson("/api/v1/knowledge-points", "{\"name\":\"判断\"}", teacher));
        long judge = id(postJson("/api/v1/questions", "{\"type\":\"TRUE_FALSE\",\"stem\":\"这道题会被漏答。\","
                + "\"difficulty\":\"EASY\",\"standardAnswer\":true,\"suggestedScore\":10,"
                + "\"knowledgePointId\":" + point + ",\"options\":[]}", teacher));
        long paper = id(postJson("/api/v1/papers", "{\"name\":\"漏答卷\",\"durationMinutes\":10,\"totalScore\":10,"
                + "\"questions\":[{\"questionId\":" + judge + ",\"score\":10}]}", teacher));
        postJson("/api/v1/papers/" + paper + "/publish", null, teacher);
        long exam = id(postJson("/api/v1/exams", examBody("漏答考试", paper), teacher));
        postJson("/api/v1/exams/" + exam + "/publish", null, teacher);

        String student = login("student");
        // 整卷不作答直接交卷：全客观题的卷子交完即出分，不需要教师批阅。
        long submission = id(postJson("/api/v1/exams/" + exam + "/submissions", null, student));
        postJson("/api/v1/submissions/" + submission + "/submit", null, student)
                .andExpect(jsonPath("$.data.objectiveScore").value(0.0));
        postJson("/api/v1/exams/" + exam + "/publish-results", null, teacher).andExpect(status().isOk());

        getJson("/api/v1/my/wrong-questions", student)
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].blank").value(true))
                .andExpect(jsonPath("$.data.items[0].stem").value(Matchers.containsString("漏答")))
                .andExpect(jsonPath("$.data.items[0].standardAnswer").value(true));

        long paperQuestionId = body(getJson("/api/v1/my/wrong-questions", student))
                .at("/data/items/0/paperQuestionId").asLong();
        postJson("/api/v1/my/wrong-questions/practice", practiceBody(paperQuestionId, "true"), student)
                .andExpect(jsonPath("$.data.answers[0].correct").value(true));
        // 练对之后仍然留在错题本里，只是标记为已掌握——错题记录本身不该消失。
        getJson("/api/v1/my/wrong-questions", student)
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].mastered").value(true));
    }

    /** 一道题的练习提交请求体。 */
    private String practiceBody(long paperQuestionId, String answer) {
        return "{\"answers\":[{\"paperQuestionId\":" + paperQuestionId + ",\"answerContent\":" + answer + "}]}";
    }

    /** 建出验收前置数据里的那份试卷和考试：单选、多选、判断、简答各一题，每题 10 分，总分 40。 */
    private long acceptanceExam(String teacher) throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        long point = id(postJson("/api/v1/knowledge-points", "{\"name\":\"Java" + suffix + "\"}", teacher));
        long single = id(postJson("/api/v1/questions", question("SINGLE_CHOICE", "单选" + suffix, "[\"A\"]", point,
                "[{\"key\":\"A\",\"content\":\"对\"},{\"key\":\"B\",\"content\":\"错\"}]"), teacher));
        long multiple = id(postJson("/api/v1/questions", question("MULTIPLE_CHOICE", "多选" + suffix, "[\"A\",\"B\"]",
                point, "[{\"key\":\"A\",\"content\":\"甲\"},{\"key\":\"B\",\"content\":\"乙\"},"
                        + "{\"key\":\"C\",\"content\":\"丙\"}]"), teacher));
        long truth = id(postJson("/api/v1/questions", question("TRUE_FALSE", "判断" + suffix, "true", point, "[]"), teacher));
        long shortAnswer = id(postJson("/api/v1/questions",
                question("SHORT_ANSWER", "简答" + suffix, "\"参考\"", point, "[]"), teacher));
        long paper = id(postJson("/api/v1/papers", "{\"name\":\"验收卷\",\"durationMinutes\":30,\"totalScore\":40,"
                + "\"questions\":[{\"questionId\":" + single + ",\"score\":10},{\"questionId\":" + multiple
                + ",\"score\":10},{\"questionId\":" + truth + ",\"score\":10},{\"questionId\":" + shortAnswer
                + ",\"score\":10}]}", teacher));
        postJson("/api/v1/papers/" + paper + "/publish", null, teacher);
        long exam = id(postJson("/api/v1/exams", examBody("Java考试", paper), teacher));
        postJson("/api/v1/exams/" + exam + "/publish", null, teacher);
        return exam;
    }

    /** 学生按验收数据作答但不交卷：单选答对、多选少选、判断答对、简答已作答。 */
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

    private long questionId(JsonNode started, int index) {
        return started.at("/data/questions/" + index + "/id").asLong();
    }

    /** 考试请求体：开放时间从一分钟前到一小时后。 */
    private String examBody(String name, long paper) {
        Instant now = Instant.now();
        return "{\"name\":\"" + name + "\",\"paperId\":" + paper + ",\"startAt\":\"" + now.minusSeconds(60)
                + "\",\"endAt\":\"" + now.plusSeconds(3600) + "\"}";
    }

    private String question(String type, String stem, String answer, long point, String options) {
        return "{\"type\":\"" + type + "\",\"stem\":\"" + stem + "\",\"difficulty\":\"MEDIUM\",\"standardAnswer\":"
                + answer + ",\"suggestedScore\":10,\"knowledgePointId\":" + point + ",\"options\":" + options + "}";
    }

    private ResultActions postJson(String path, String body, String token) throws Exception {
        var request = post(path).header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(request);
    }
    private ResultActions putJson(String path, String body, String token) throws Exception {
        return mockMvc.perform(put(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private ResultActions getJson(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token));
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
