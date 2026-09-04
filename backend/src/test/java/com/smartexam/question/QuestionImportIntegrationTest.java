package com.smartexam.question;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
 * 题库批量导入的集成测试。
 *
 * <p>重点验证五件事，每一件都对应一个真实会出问题的地方：
 * <ol>
 *   <li><b>预览不写库、预览数与实际导入数一致。</b>这是本功能唯一的安全承诺，
 *       一旦预览说 18 实际进 12，教师就再也不会相信这个界面；</li>
 *   <li><b>导入的题目必须通过与手工出题相同的校验。</b>用「标准答案指向不存在的选项」
 *       这一行来证明——它在题库里是一颗定时炸弹，考试时会给全班判 0 分；</li>
 *   <li><b>逐行失败不影响其他行</b>，且错误信息带教师在 Excel 里看到的行号；</li>
 *   <li><b>模板自己能被导入。</b>模板和解析器一旦漂移，教师下载下来照着填反而报错；</li>
 *   <li><b>真实表格的脏细节：</b>BOM、制表符分隔、引号里的逗号和换行。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class QuestionImportIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    /** 每个用例都从干净题库开始，否则判重逻辑会把上一个用例导入的题目算进来。 */
    @BeforeEach void clear() {
        jdbc.update("DELETE FROM question_option"); jdbc.update("DELETE FROM question");
        jdbc.update("DELETE FROM knowledge_point");
    }

    /**
     * 主流程：先预览再导入，两次的可导入行数必须一致。
     *
     * <p>这份表格刻意塞进了四个真实写法：题干里带逗号（必须靠引号保住一格）、
     * 选项不写「A.」序号（按顺序补成 A–D）、多选答案写成 {@code AB}、判断题答案写成「正确」、
     * 难度和分值留空（走默认值）。这些都不是构造出来的边界，是手工整理表格的常态。
     */
    @Test void previewsFirstThenImportsTheSameRows() throws Exception {
        String teacher = login("teacher");
        createPoint("导入测试", teacher);
        String csv = """
                题型,题干,难度,知识点,分值,选项,标准答案,解析,标签
                单选题,"下列哪一项是基本类型，不是引用类型？",易,导入测试,10,int|String|Integer|List,A,只有 int 是基本类型。,"Java, 类型"
                多选题,下列哪些是 List 的实现类？,,导入测试,,A. ArrayList|B. LinkedList|C. HashMap,AB,,集合
                判断题,String 对象不可变。,难,导入测试,5,,正确,,
                简答题,简述封装的作用。,,导入测试,8,,隐藏实现细节并只暴露必要接口。,按要点给分。,面向对象
                """;

        // 第一步：预览。dryRun 不传，默认就是预览——这是刻意选的默认值。
        postImport(csv, null, teacher)
                .andExpect(jsonPath("$.data.dryRun").value(true))
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.imported").value(4))
                .andExpect(jsonPath("$.data.skipped").value(0))
                .andExpect(jsonPath("$.data.failed").value(0))
                // 行号是原文行号（含表头），与教师在 Excel 里看到的一致。
                .andExpect(jsonPath("$.data.rows[0].line").value(2))
                .andExpect(jsonPath("$.data.rows[3].line").value(5))
                .andExpect(jsonPath("$.data.rows[0].type").value("SINGLE_CHOICE"))
                // 预览阶段没有题目 ID，因为什么都没写。
                .andExpect(jsonPath("$.data.rows[0].questionId").doesNotExist());
        assertEquals(0, count("question"), "预览绝不能写库");

        // 第二步：正式导入。可导入行数必须和预览完全一致。
        postImport(csv, false, teacher)
                .andExpect(jsonPath("$.data.dryRun").value(false))
                .andExpect(jsonPath("$.data.imported").value(4))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.rows[0].questionId").isNumber());
        assertEquals(4, count("question"));

        // 逐项核对映射结果：选项序号补全、答案形态、默认难度与默认分值。
        JsonNode single = query("keyword=基本类型", teacher).at("/data/items/0");
        assertEquals("EASY", single.path("difficulty").asText());
        assertEquals(4, single.path("options").size());
        assertEquals("A", single.path("options").get(0).path("key").asText());
        assertEquals("int", single.path("options").get(0).path("content").asText());
        assertEquals("A", single.path("standardAnswer").get(0).asText());

        JsonNode multiple = query("keyword=List 的实现类", teacher).at("/data/items/0");
        assertEquals("MEDIUM", multiple.path("difficulty").asText(), "难度留空按中等处理");
        assertEquals(10, multiple.path("suggestedScore").asInt(), "分值留空按 10 分处理");
        assertEquals("[\"A\",\"B\"]", multiple.path("standardAnswer").toString(), "AB 要拆成两个选项键");

        JsonNode judge = query("keyword=不可变", teacher).at("/data/items/0");
        assertTrue(judge.path("standardAnswer").asBoolean(), "「正确」要转成布尔 true");
        assertEquals(0, judge.path("options").size(), "判断题不带选项");
        assertEquals("HARD", judge.path("difficulty").asText());
    }

    /**
     * 模板自身必须能被导入接口原样接受。
     *
     * <p>这一条是防漂移测试：模板的表头和取值写法只要与解析器脱节，
     * 教师下载下来照着填就会得到一堆报错，而这种问题在代码评审里最不容易被发现。
     * 顺带覆盖了 UTF-8 BOM——模板必须带 BOM，否则 Excel 打开是乱码。
     */
    @Test void theDownloadableTemplateImportsCleanly() throws Exception {
        String teacher = login("teacher");
        // 模板里用的是「示例知识点」，导入前必须真的存在——知识点不会被自动创建。
        createPoint("示例知识点", teacher);

        String template = mockMvc.perform(get("/api/v1/questions/import/template")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(template.startsWith("\uFEFF"), "模板必须带 BOM，否则 Excel 打开中文表头是乱码");
        assertTrue(template.contains("题型,题干,难度,知识点,分值,选项,标准答案"), "模板表头必须是解析器认得的列名");

        postImport(template, false, teacher)
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.imported").value(5))
                .andExpect(jsonPath("$.data.failed").value(0));
        // 五行示例覆盖五种题型，导入后题库里应各有一道。
        assertEquals(5, count("question"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM question WHERE type='PROGRAMMING'", Integer.class));
        assertEquals(8, jdbc.queryForObject(
                "SELECT COUNT(*) FROM question_option", Integer.class), "两道选择题共 8 个选项");
    }

    /**
     * 六种坏行各自失败，好行照样进库，且错误信息带行号。
     *
     * <p>第 5 行是这组用例里最重要的一条：标准答案写了 C，而选项只有 A 和 B。
     * 它在格式上完全正确，只有跑一遍业务校验才能发现——这就是「导入不能自己写一套宽松校验」
     * 的具体理由。如果放过它，题库里会多一道永远判 0 分的题。
     */
    @Test void reportsEachBadRowWithItsLineNumberAndKeepsGoodRows() throws Exception {
        String teacher = login("teacher");
        createPoint("导入测试", teacher);
        String csv = """
                题型,题干,难度,知识点,分值,选项,标准答案
                火箭题,题干甲,易,导入测试,10,,答案
                单选题,,易,导入测试,10,A. 甲|B. 乙,A
                单选题,题干丙,易,不存在的知识点,10,A. 甲|B. 乙,A
                单选题,题干丁,易,导入测试,10,A. 甲|B. 乙,C
                单选题,题干戊,易,导入测试,abc,A. 甲|B. 乙,A
                判断题,题干己,易,导入测试,10,,也许
                单选题,题干庚,易,导入测试,10,A. 甲|B. 乙,A
                """;

        postImport(csv, false, teacher)
                .andExpect(jsonPath("$.data.total").value(7))
                .andExpect(jsonPath("$.data.imported").value(1))
                .andExpect(jsonPath("$.data.failed").value(6))
                .andExpect(jsonPath("$.data.rows[0].line").value(2))
                .andExpect(jsonPath("$.data.rows[0].outcome").value("FAILED"))
                .andExpect(jsonPath("$.data.rows[0].message").value(Matchers.containsString("无法识别的题型")))
                .andExpect(jsonPath("$.data.rows[1].message").value(Matchers.containsString("题干不能为空")))
                .andExpect(jsonPath("$.data.rows[2].message").value(Matchers.containsString("不存在的知识点")))
                // 格式合法但业务不合法：这一条只有走 QuestionService 的校验才拦得下来。
                .andExpect(jsonPath("$.data.rows[3].message").value(Matchers.containsString("标准答案必须引用已有选项")))
                .andExpect(jsonPath("$.data.rows[4].message").value(Matchers.containsString("不是数字")))
                .andExpect(jsonPath("$.data.rows[5].message").value(Matchers.containsString("无法识别")))
                .andExpect(jsonPath("$.data.rows[6].line").value(8))
                .andExpect(jsonPath("$.data.rows[6].outcome").value("IMPORTED"));
        // 一行失败不能连累已经成功的行：本类刻意不加 @Transactional，正是为了这一点。
        assertEquals(1, count("question"));
    }

    /**
     * 重复题干只跳过、不算失败，而且文件内部的重复在预览阶段就能看出来。
     *
     * <p>把同一个文件导入两次是最常见的误操作。只查数据库不够：预览时文件里的两行都还没入库，
     * 只查库会双双通过，随后正式导入却只成功一行——预览就不准了，而预览准确是本功能的全部价值。
     */
    @Test void skipsDuplicatesInsideTheFileAndAgainstTheBank() throws Exception {
        String teacher = login("teacher");
        createPoint("导入测试", teacher);
        String first = "题型,题干,知识点,标准答案\n简答题,题干甲,导入测试,参考答案\n";
        postImport(first, false, teacher).andExpect(jsonPath("$.data.imported").value(1));

        // 同一个文件再导一次：题库里已经有了，整批跳过而不是报错。
        postImport(first, false, teacher)
                .andExpect(jsonPath("$.data.imported").value(0))
                .andExpect(jsonPath("$.data.skipped").value(1))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.rows[0].outcome").value("SKIPPED"))
                .andExpect(jsonPath("$.data.rows[0].message").value(Matchers.containsString("题库中已存在")));

        // 文件内部重复：预览和实际导入必须给出同一组数字。
        String twice = "题型,题干,知识点,标准答案\n简答题,题干乙,导入测试,参考答案\n简答题,题干乙,导入测试,参考答案\n";
        postImport(twice, null, teacher)
                .andExpect(jsonPath("$.data.imported").value(1))
                .andExpect(jsonPath("$.data.skipped").value(1))
                .andExpect(jsonPath("$.data.rows[1].message").value(Matchers.containsString("本次导入")));
        postImport(twice, false, teacher)
                .andExpect(jsonPath("$.data.imported").value(1))
                .andExpect(jsonPath("$.data.skipped").value(1));
        assertEquals(2, count("question"));
    }

    /**
     * 制表符分隔 + BOM + 引号里的换行，三个来自真实表格的脏细节。
     *
     * <p>从 Excel 里直接复制粘贴得到的是制表符分隔，另存为文件才是逗号；带换行的长题干
     * 在真实题库里很常见。第二行的行号必须是 5 而不是 3——多行题干占掉了 2、3、4 三行，
     * 报错时给错行号，教师就会去改一道无关的题。
     */
    @Test void acceptsTabSeparatedContentWithBomAndQuotedNewlines() throws Exception {
        String teacher = login("teacher");
        createPoint("导入测试", teacher);
        String tsv = "\uFEFF题型\t题干\t知识点\t标准答案\n"
                + "简答题\t\"请说明下面两点：\n第一点\n第二点\"\t导入测试\t参考答案文本\n"
                + "判断题\t单行题干\t导入测试\t错误\n";

        postImport(tsv, false, teacher)
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.imported").value(2))
                .andExpect(jsonPath("$.data.rows[0].line").value(2))
                .andExpect(jsonPath("$.data.rows[0].stem").value(Matchers.containsString("第二点")))
                // 多行题干占了 3 行，下一条记录的行号必须跳到 5。
                .andExpect(jsonPath("$.data.rows[1].line").value(5));

        JsonNode judge = query("keyword=单行题干", teacher).at("/data/items/0");
        assertEquals(false, judge.path("standardAnswer").asBoolean(), "「错误」要转成布尔 false");
    }

    /** 权限与整表级校验：401、403、空内容、缺列、只有表头、超行数上限。 */
    @Test void enforcesTeacherRoleAndWholeTableValidation() throws Exception {
        String teacher = login("teacher");
        createPoint("导入测试", teacher);
        String csv = "题型,题干,知识点,标准答案\n简答题,题干甲,导入测试,参考答案\n";

        mockMvc.perform(post("/api/v1/questions/import").contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(csv, true)))
                .andExpect(status().isUnauthorized());
        postImport(csv, true, login("student")).andExpect(status().isForbidden());
        postImport(csv, true, login("admin")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/questions/import/template")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/questions/import/template")
                .header("Authorization", "Bearer " + login("student"))).andExpect(status().isForbidden());

        // 空内容由 Bean Validation 拦住，与其他接口的缺字段错误同一个 code。
        postImport("   ", true, teacher).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        // 缺必填列时整批失败并点名缺哪几列，而不是让每一行都报同一句错。
        postImport("名称,内容\n甲,乙\n", true, teacher).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_INVALID_FORMAT"))
                .andExpect(jsonPath("$.message").value(Matchers.allOf(
                        Matchers.containsString("题型"), Matchers.containsString("标准答案"))));
        postImport("题型,题干,知识点,标准答案\n", true, teacher).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_INVALID_FORMAT"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("只有表头")));

        StringBuilder tooMany = new StringBuilder("题型,题干,知识点,标准答案\n");
        for (int index = 0; index < 201; index++) {
            tooMany.append("简答题,题干").append(index).append(",导入测试,参考答案\n");
        }
        postImport(tooMany.toString(), true, teacher).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_TOO_MANY_ROWS"));
        assertEquals(0, count("question"), "以上失败路径都不应该写库");
    }

    /** 发一次导入请求。{@code dryRun} 传 null 表示不带这个字段，用来验证默认值就是预览。 */
    private ResultActions postImport(String content, Boolean dryRun, String token) throws Exception {
        return mockMvc.perform(post("/api/v1/questions/import").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(requestBody(content, dryRun)));
    }

    /** 拼请求体。用 Jackson 生成而不是手工拼字符串，因为表格正文里有引号、换行和制表符。 */
    private String requestBody(String content, Boolean dryRun) {
        var node = json.createObjectNode().put("content", content);
        if (dryRun != null) node.put("dryRun", dryRun);
        return node.toString();
    }

    /** 按查询串取题库列表，用于核对导入后的字段。 */
    private JsonNode query(String queryString, String token) throws Exception {
        return body(mockMvc.perform(get("/api/v1/questions?" + queryString)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
    }

    /** 新建知识点并返回 ID。导入不会自动创建知识点，因此每个用例都要先建好。 */
    private long createPoint(String name, String token) throws Exception {
        return body(mockMvc.perform(post("/api/v1/knowledge-points")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.createObjectNode().put("name", name).toString()))
                .andExpect(status().isOk())).at("/data/id").asLong();
    }

    /** 统计某张表的行数。 */
    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    /** 把响应体解析成 JSON 树。 */
    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** 以指定账号登录并返回访问令牌。 */
    private String login(String username) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"ExamDemo123!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).at("/data/accessToken").asText();
    }
}
