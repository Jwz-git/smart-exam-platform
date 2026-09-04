package com.smartexam.exam;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 试卷导出的集成测试。
 *
 * <p>三件事必须成立：
 * <ol>
 *   <li><b>不含答案的 Markdown 里真的没有答案。</b>这一份是要发给学生的，
 *       泄一个字都不行——因此用「标准答案的字面内容不出现在正文里」来断言，
 *       而不是只看有没有「参考答案」这个小标题；</li>
 *   <li><b>CSV 能被题库导入接口原样读回去。</b>导出与导入是一对，
 *       列名、选项写法、答案形态只要有一处不一致，导出的文件就是个死文件。
 *       这里把导出结果直接喂给 {@code POST /questions/import} 验证；</li>
 *   <li><b>归属与格式校验。</b>别人的试卷 403、不存在 404、未登录 401、格式不支持 400。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaperExportIntegrationTest {
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
     * Markdown 导出：不含答案的版本可直接发给学生，含答案的版本附「参考答案与解析」。
     *
     * <p>试卷刻意由「两道单选 + 一道判断」组成，用来验证大题分组：连续同题型合并成一个大题，
     * 标题里给出中文序号、小题数和分值合计，与学生答题页看到的分组是同一套算法。
     */
    @Test void exportsPrintableMarkdownWithAndWithoutAnswers() throws Exception {
        String teacher = login("teacher");
        long paper = examPaper(teacher);

        String plain = text(export(paper, "md", false, teacher)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                // 文件名含中文，必须按 RFC 5987 编码，否则部分客户端会存成乱码文件名。
                .andExpect(header().string("Content-Disposition", Matchers.containsString("filename*=UTF-8''"))));
        assertTrue(plain.contains("# 导出用卷"), "标题应是试卷名");
        assertTrue(plain.contains("总分 25 分"), "卷头要给出总分");
        assertTrue(plain.contains("## 一、单选题（本大题共 2 小题，每小题 10 分，共 20 分）"), "连续同题型合并为一个大题");
        assertTrue(plain.contains("## 二、判断题（本大题共 1 小题，每小题 5 分，共 5 分）"));
        assertTrue(plain.contains("- A. 甲"), "选择题要列出选项");
        assertTrue(plain.contains("- 正确 / 错误"), "判断题要给出作答形式");
        assertFalse(plain.contains("参考答案"), "不含答案的版本不能出现答案区");
        assertFalse(plain.contains("只有 int 是基本类型"), "解析同样属于答案，不能出现");

        String withAnswers = text(export(paper, "md", true, teacher).andExpect(status().isOk()));
        assertTrue(withAnswers.contains("## 参考答案与解析"));
        assertTrue(withAnswers.contains("答案：A"));
        assertTrue(withAnswers.contains("答案：正确"), "判断题答案要写成中文，不是 true");
        assertTrue(withAnswers.contains("解析：只有 int 是基本类型"));
    }

    /**
     * CSV 导出必须能被题库导入接口原样读回去——这是「导出」这个功能的成色所在。
     *
     * <p>换一位教师导入：判重的范围是 {@code created_by}，同一位教师导回去会整批「跳过」，
     * 看不出解析是否真的成功。换人导入既避开判重，也正好演示了本功能的真实用途——
     * 把一份试卷的题目搬进另一位教师的题库。
     */
    @Test void exportedCsvCanBeImportedBackIntoTheQuestionBank() throws Exception {
        String teacher = login("teacher");
        long paper = examPaper(teacher);

        String csv = text(export(paper, "csv", false, teacher)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv")));
        assertTrue(csv.startsWith("﻿"), "CSV 必须带 BOM，否则 Excel 打开中文表头是乱码");
        assertTrue(csv.contains("题型,题干,难度,知识点,分值,选项,标准答案,解析,标签"), "表头必须与导入模板一致");
        // CSV 一定带答案：导入接口的「标准答案」是必填列，不带就导不回去。
        assertTrue(csv.contains(",A,"), "单选答案写成选项键");
        assertTrue(csv.contains(",正确,") || csv.contains(",正确\n"), "判断题答案写成中文");
        assertTrue(csv.contains("A. 甲|B. 乙"), "选项用竖线连接并带序号");

        String other = login("teacher2");
        postImport(csv, false, other)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.imported").value(3))
                .andExpect(jsonPath("$.data.skipped").value(0))
                .andExpect(jsonPath("$.data.failed").value(0));

        // 回导之后的字段要与导出前一致：题型、选项、答案形态、分值（取的是试卷里的分值）。
        JsonNode judge = body(mockMvc.perform(get("/api/v1/questions?keyword=不可修改")
                .header("Authorization", "Bearer " + other))).at("/data/items/0");
        assertTrue(judge.path("standardAnswer").asBoolean(), "「正确」要还原成布尔 true");
        assertTrue(judge.path("options").isEmpty(), "判断题不带选项");
        org.junit.jupiter.api.Assertions.assertEquals(5.0, judge.path("suggestedScore").asDouble(),
                "分值取自试卷里的分值");
        JsonNode single = body(mockMvc.perform(get("/api/v1/questions?keyword=基本数据类型")
                .header("Authorization", "Bearer " + other))).at("/data/items/0");
        org.junit.jupiter.api.Assertions.assertEquals("A", single.at("/standardAnswer/0").asText());
        org.junit.jupiter.api.Assertions.assertEquals(2, single.path("options").size());
        org.junit.jupiter.api.Assertions.assertEquals("EASY", single.path("difficulty").asText(),
                "难度取自题库当前值（快照里没有这一列）");
    }

    /** 权限、格式与不存在的试卷。 */
    @Test void enforcesOwnershipAndFormat() throws Exception {
        String teacher = login("teacher");
        long paper = examPaper(teacher);

        export(paper, "md", false, login("teacher2")).andExpect(status().isForbidden());
        export(paper, "md", false, login("student")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/papers/{id}/export", paper)).andExpect(status().isUnauthorized());
        export(999999, "md", false, teacher).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
        export(paper, "pdf", false, teacher).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXPORT_FORMAT_UNSUPPORTED"));
        // 不传 format 时默认导 Markdown。
        assertTrue(text(export2("/api/v1/papers/" + paper + "/export", teacher)).startsWith("# 导出用卷"));
    }

    /** 建一份「两道单选 + 一道判断」的试卷，总分 25 分。 */
    private long examPaper(String teacher) throws Exception {
        long point = id(postJson("/api/v1/knowledge-points", "{\"name\":\"Java 基础\"}", teacher));
        long first = id(postJson("/api/v1/questions", "{\"type\":\"SINGLE_CHOICE\",\"stem\":\"下列哪一项是基本数据类型？\","
                + "\"difficulty\":\"EASY\",\"standardAnswer\":[\"A\"],\"explanation\":\"只有 int 是基本类型。\","
                + "\"suggestedScore\":10,\"knowledgePointId\":" + point + ",\"tags\":\"Java, 类型\","
                + "\"options\":[{\"key\":\"A\",\"content\":\"甲\"},{\"key\":\"B\",\"content\":\"乙\"}]}", teacher));
        long second = id(postJson("/api/v1/questions", "{\"type\":\"SINGLE_CHOICE\",\"stem\":\"下列哪一项不是关键字？\","
                + "\"difficulty\":\"MEDIUM\",\"standardAnswer\":[\"B\"],\"suggestedScore\":10,"
                + "\"knowledgePointId\":" + point + ",\"options\":[{\"key\":\"A\",\"content\":\"甲\"},"
                + "{\"key\":\"B\",\"content\":\"乙\"}]}", teacher));
        long judge = id(postJson("/api/v1/questions", "{\"type\":\"TRUE_FALSE\",\"stem\":\"String 对象不可修改。\","
                + "\"difficulty\":\"EASY\",\"standardAnswer\":true,\"suggestedScore\":10,"
                + "\"knowledgePointId\":" + point + ",\"options\":[]}", teacher));
        return id(postJson("/api/v1/papers", "{\"name\":\"导出用卷\",\"durationMinutes\":45,\"totalScore\":25,"
                + "\"questions\":[{\"questionId\":" + first + ",\"score\":10},{\"questionId\":" + second
                + ",\"score\":10},{\"questionId\":" + judge + ",\"score\":5}]}", teacher));
    }

    /** 发一次导出请求。 */
    private ResultActions export(long paper, String format, boolean withAnswers, String token) throws Exception {
        return mockMvc.perform(get("/api/v1/papers/{id}/export", paper)
                .param("format", format).param("withAnswers", String.valueOf(withAnswers))
                .header("Authorization", "Bearer " + token));
    }

    /** 不带任何查询参数的导出，用来验证默认格式。 */
    private ResultActions export2(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token));
    }

    /** 把导出的文件正文取成字符串。 */
    private String text(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    /** 把一份 CSV 提交给题库导入接口。 */
    private ResultActions postImport(String content, boolean dryRun, String token) throws Exception {
        String requestBody = json.createObjectNode().put("content", content).put("dryRun", dryRun).toString();
        return mockMvc.perform(post("/api/v1/questions/import").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(requestBody));
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
