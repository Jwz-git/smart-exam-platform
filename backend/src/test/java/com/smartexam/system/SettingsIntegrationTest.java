package com.smartexam.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
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
 * 可编辑系统设置的集成测试。
 *
 * <p>这一组用例的重点不是「表单能不能存」，而是<b>改完之后真的生效</b>——
 * 这正是本页原先做成只读时担心的问题。因此每一项都用一个能观测到的业务结果来验证：
 * 改及格线看成绩接口的及格率变没变，改导入行数上限看导入接口拦不拦。
 *
 * <p>另外两条：只有管理员能改（教师只能看），以及未列入白名单的键一律拒绝。
 *
 * <p>每个用例前后都把设置恢复默认，否则覆盖值会留在共享的 H2 库里影响别的测试类。
 * 恢复走接口而不是直接 DELETE 表，因为 {@code SettingsStore} 有内存缓存，
 * 只删表不会让缓存失效——这一点本身也值得被测试固定下来。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SettingsIntegrationTest {
    /** 白名单里的全部键，恢复默认时逐个置空。 */
    private static final String[] KEYS = {"exam.pass-ratio-percent", "security.access-token-minutes",
            "question.import-max-rows", "question.import-skip-duplicate-stem",
            "paper.auto-compose-max-questions", "practice.batch-size"};

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void resetBefore() throws Exception { restoreDefaults(); }

    @AfterEach void resetAfter() throws Exception { restoreDefaults(); }

    /**
     * 读接口：六项可编辑设置各自给出当前值、默认值和来源，且响应里没有任何密钥。
     *
     * <p>「当前值 + 默认值 + 是否被覆盖」三件信息缺一不可：只显示当前值的话，
     * 管理员改错之后不知道原来是多少，也看不出这一项到底改过没有。
     */
    @Test void listsEditableSettingsWithDefaultsAndNoSecrets() throws Exception {
        String admin = login("admin");
        String response = getJson("/api/v1/system/settings", admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.editable.length()").value(6))
                .andExpect(jsonPath("$.data.editable[0].key").value("exam.pass-ratio-percent"))
                .andExpect(jsonPath("$.data.editable[0].label").value("及格线"))
                .andExpect(jsonPath("$.data.editable[0].type").value("DECIMAL"))
                .andExpect(jsonPath("$.data.editable[0].value").value("60"))
                .andExpect(jsonPath("$.data.editable[0].defaultValue").value("60"))
                .andExpect(jsonPath("$.data.editable[0].overridden").value(false))
                .andExpect(jsonPath("$.data.editable[0].min").value(0))
                .andExpect(jsonPath("$.data.editable[0].max").value(100))
                .andExpect(jsonPath("$.data.editable[0].updatedBy").doesNotExist())
                .andExpect(jsonPath("$.data.editable[3].type").value("BOOLEAN"))
                .andExpect(jsonPath("$.data.editable[3].value").value("true"))
                // 只读部分仍然如实展示，且与可编辑项取同一个值。
                .andExpect(jsonPath("$.data.exam.passRatioPercent").value(60))
                .andExpect(jsonPath("$.data.security.accessTokenMinutes").value(60))
                .andExpect(jsonPath("$.data.ai.configured").value(true))
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("test-only-key"), "响应里绝不能出现 AI 密钥");
        assertFalse(response.contains("jdbc:"), "响应里绝不能出现数据库连接串");

        // 教师只能看：读得到，改不了。
        getJson("/api/v1/system/settings", login("teacher")).andExpect(status().isOk());
        getJson("/api/v1/system/settings", login("student")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/system/settings")).andExpect(status().isUnauthorized());
    }

    /**
     * 改及格线之后，成绩接口的及格率立刻按新线重算——不需要重启。
     *
     * <p>用的是验收数据里那组 20、10、10、0 分：满分 20 的卷子，60% 及格线是 12 分，
     * 只有一人达标（25.0%）；把及格线改成 50% 后线降到 10 分，三人达标（75.0%）；
     * 恢复默认又回到 25.0%。这三个数字把「改了真的生效」和「恢复默认真的还原」都钉住了。
     */
    @Test void changingPassLineImmediatelyChangesPassRate() throws Exception {
        String teacher = login("teacher");
        long exam = rankingExam(teacher);
        getJson("/api/v1/exams/" + exam + "/results", teacher)
                .andExpect(jsonPath("$.data.passRate").value(25.0));

        String admin = login("admin");
        putSettings("{\"exam.pass-ratio-percent\":\"50\"}", admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(1))
                .andExpect(jsonPath("$.data.settings.editable[0].value").value("50"))
                .andExpect(jsonPath("$.data.settings.editable[0].overridden").value(true))
                .andExpect(jsonPath("$.data.settings.editable[0].updatedBy").value("系统管理员"))
                .andExpect(jsonPath("$.data.settings.exam.passRatioPercent").value(50));

        getJson("/api/v1/exams/" + exam + "/results", teacher)
                .andExpect(jsonPath("$.data.passRate").value(75.0));
        // 统计分析页的及格分数线与成绩页同源，必须一起变。
        getJson("/api/v1/stats/exams/" + exam, teacher)
                .andExpect(jsonPath("$.data.passScore").value(10.0))
                .andExpect(jsonPath("$.data.passRate").value(75.0));

        // 提交同一个值不算修改，changed 应为 0——界面因此能如实说「没有改动」。
        putSettings("{\"exam.pass-ratio-percent\":\"50\"}", admin)
                .andExpect(jsonPath("$.data.changed").value(1 - 1));

        // 恢复默认：传空串即删除覆盖行，回到环境变量给的 60%。
        putSettings("{\"exam.pass-ratio-percent\":\"\"}", admin)
                .andExpect(jsonPath("$.data.changed").value(1))
                .andExpect(jsonPath("$.data.settings.editable[0].value").value("60"))
                .andExpect(jsonPath("$.data.settings.editable[0].overridden").value(false));
        getJson("/api/v1/exams/" + exam + "/results", teacher)
                .andExpect(jsonPath("$.data.passRate").value(25.0));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM system_setting", Integer.class),
                "恢复默认是删掉覆盖行，而不是写回一个当时的默认值");
    }

    /**
     * 改导入行数上限之后，导入接口立刻按新上限拦人；错误信息里的数字也跟着变。
     *
     * <p>选这一项是因为它的效果最直观：同一份 12 行的表格，上限 200 时能导入，
     * 上限 10 时整批被拒，且提示里写的是「最多导入 10 行」而不是写死的 200。
     */
    @Test void changingImportLimitImmediatelyChangesImportBehaviour() throws Exception {
        String teacher = login("teacher");
        long point = id(postJson("/api/v1/knowledge-points", "{\"name\":\"设置\"}", teacher));
        assertEquals(true, point > 0);
        StringBuilder csv = new StringBuilder("题型,题干,知识点,标准答案\n");
        for (int index = 0; index < 12; index++) {
            csv.append("简答题,设置用题").append(index).append(",设置,参考答案\n");
        }
        // 默认上限 200：12 行可以预览通过。
        postImport(csv.toString(), teacher).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(12));

        putSettings("{\"question.import-max-rows\":\"10\"}", login("admin"))
                .andExpect(jsonPath("$.data.changed").value(1));
        postImport(csv.toString(), teacher).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_TOO_MANY_ROWS"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("最多导入 10 行")));
    }

    /** 校验与权限：越界、非整数、非法布尔、未知键一律 400；教师改设置 403。 */
    @Test void validatesValuesAndRestrictsWritesToAdmin() throws Exception {
        String admin = login("admin");
        putSettings("{\"exam.pass-ratio-percent\":\"120\"}", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_INVALID"))
                .andExpect(jsonPath("$.message").value(Matchers.allOf(
                        Matchers.containsString("及格线"), Matchers.containsString("0—100%"))));
        putSettings("{\"question.import-max-rows\":\"12.5\"}", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("必须是整数")));
        putSettings("{\"question.import-max-rows\":\"abc\"}", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("不是数字")));
        putSettings("{\"question.import-skip-duplicate-stem\":\"maybe\"}", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("true 或 false")));
        putSettings("{\"app.security.jwt-secret\":\"whatever\"}", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_UNKNOWN"));
        putSettings("{\"ai.api-key\":\"sk-test\"}", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_UNKNOWN"));
        putSettings("{}", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 一次提交里有一项越界，整批都不生效：不能留下「改了一半」的状态。
        putSettings("{\"practice.batch-size\":\"5\",\"exam.pass-ratio-percent\":\"999\"}", admin)
                .andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM system_setting", Integer.class),
                "整批校验失败时一行都不该写入");

        putSettings("{\"practice.batch-size\":\"5\"}", login("teacher")).andExpect(status().isForbidden());
        putSettings("{\"practice.batch-size\":\"5\"}", login("student")).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/system/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"practice.batch-size\":\"5\"}}")).andExpect(status().isUnauthorized());
    }

    /** 把全部设置恢复默认。管理员一次提交，顺带让 {@code SettingsStore} 的缓存失效。 */
    private void restoreDefaults() throws Exception {
        StringBuilder values = new StringBuilder("{");
        for (int index = 0; index < KEYS.length; index++) {
            if (index > 0) values.append(',');
            values.append('"').append(KEYS[index]).append("\":\"\"");
        }
        putSettings(values.append('}').toString(), login("admin")).andExpect(status().isOk());
    }

    /** 建一场四人答卷、分数为 20/10/10/0 的考试，用于观察及格率变化。 */
    private long rankingExam(String teacher) throws Exception {
        jdbc.update("DELETE FROM practice_attempt");
        jdbc.update("DELETE FROM submission_answer"); jdbc.update("DELETE FROM submission");
        jdbc.update("DELETE FROM exam"); jdbc.update("DELETE FROM paper_question"); jdbc.update("DELETE FROM paper");
        jdbc.update("DELETE FROM question_option"); jdbc.update("DELETE FROM question");
        jdbc.update("DELETE FROM knowledge_point");
        long point = id(postJson("/api/v1/knowledge-points", "{\"name\":\"及格线\"}", teacher));
        long single = id(postJson("/api/v1/questions", "{\"type\":\"SINGLE_CHOICE\",\"stem\":\"单选\","
                + "\"difficulty\":\"MEDIUM\",\"standardAnswer\":[\"A\"],\"suggestedScore\":10,"
                + "\"knowledgePointId\":" + point + ",\"options\":[{\"key\":\"A\",\"content\":\"对\"},"
                + "{\"key\":\"B\",\"content\":\"错\"}]}", teacher));
        long truth = id(postJson("/api/v1/questions", "{\"type\":\"TRUE_FALSE\",\"stem\":\"判断\","
                + "\"difficulty\":\"MEDIUM\",\"standardAnswer\":true,\"suggestedScore\":10,"
                + "\"knowledgePointId\":" + point + ",\"options\":[]}", teacher));
        long paper = id(postJson("/api/v1/papers", "{\"name\":\"及格线卷\",\"durationMinutes\":30,\"totalScore\":20,"
                + "\"questions\":[{\"questionId\":" + single + ",\"score\":10},{\"questionId\":" + truth
                + ",\"score\":10}]}", teacher));
        postJson("/api/v1/papers/" + paper + "/publish", null, teacher);
        Instant now = Instant.now();
        long exam = id(postJson("/api/v1/exams", "{\"name\":\"及格线考试\",\"paperId\":" + paper + ",\"startAt\":\""
                + now.minusSeconds(60) + "\",\"endAt\":\"" + now.plusSeconds(3600) + "\"}", teacher));
        postJson("/api/v1/exams/" + exam + "/publish", null, teacher);
        submitWith(exam, login("student"), "[\"A\"]", "true");
        submitWith(exam, login("student2"), "[\"A\"]", "false");
        submitWith(exam, login("student3"), "[\"B\"]", "true");
        submitWith(exam, login("student4"), null, null);
        return exam;
    }

    /** 开始作答、按给定答案作答（传 null 表示整卷不答）并交卷。 */
    private void submitWith(long exam, String student, String first, String second) throws Exception {
        JsonNode started = body(postJson("/api/v1/exams/" + exam + "/submissions", null, student));
        long submission = started.at("/data/id").asLong();
        if (first != null) {
            mockMvc.perform(put("/api/v1/submissions/" + submission + "/answers")
                    .header("Authorization", "Bearer " + student).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"answers\":[{\"paperQuestionId\":" + started.at("/data/questions/0/id").asLong()
                            + ",\"answerContent\":" + first + "},{\"paperQuestionId\":"
                            + started.at("/data/questions/1/id").asLong() + ",\"answerContent\":" + second + "}]}"))
                    .andExpect(status().isOk());
        }
        postJson("/api/v1/submissions/" + submission + "/submit", null, student).andExpect(status().isOk());
    }

    /** 发一次设置修改请求。 */
    private ResultActions putSettings(String values, String token) throws Exception {
        return mockMvc.perform(put("/api/v1/system/settings").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"values\":" + values + "}"));
    }

    /** 预览一次题库导入，用来观察行数上限是否生效。 */
    private ResultActions postImport(String content, String token) throws Exception {
        return mockMvc.perform(post("/api/v1/questions/import").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.createObjectNode().put("content", content).toString()));
    }

    private ResultActions postJson(String path, String body, String token) throws Exception {
        var request = post(path).header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(request);
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
