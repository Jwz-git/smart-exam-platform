package com.smartexam.question;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartexam.common.DomainException;
import com.smartexam.common.SettingsCatalog;
import com.smartexam.common.SettingsStore;
import com.smartexam.question.QuestionImportModels.ImportRequest;
import com.smartexam.question.QuestionImportModels.ImportResult;
import com.smartexam.question.QuestionImportModels.Outcome;
import com.smartexam.question.QuestionImportModels.RowResult;
import com.smartexam.question.QuestionModels.Difficulty;
import com.smartexam.question.QuestionModels.OptionRequest;
import com.smartexam.question.QuestionModels.QuestionRequest;
import com.smartexam.question.QuestionModels.QuestionView;
import com.smartexam.question.QuestionModels.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 题库批量导入：解析表格 → 逐行映射成题目请求 → 走与手工出题完全相同的校验 → 逐行写库。
 *
 * <p>题库现在有三条入口：手工新增、AI 草稿确认、批量导入。三条都收敛到
 * {@link QuestionService}，没有任何一条能绕过 {@link QuestionService#validateDraft} ——
 * 这是本功能最重要的约束。批量导入尤其危险：它一次写入几十道题，如果自己写一套宽松的校验，
 * 一道「标准答案指向不存在选项」的题会静静躺在题库里，直到某场考试给全班判了 0 分才暴露。
 *
 * <p>四条刻意的设计选择：
 * <ol>
 *   <li><b>默认只预览。</b>{@link ImportRequest#preview()} 缺省为 true，写库必须显式传
 *       {@code dryRun=false}。漏传参数的后果应该是「什么都没发生」；</li>
 *   <li><b>逐行成败独立，不整批回滚。</b>本类<b>刻意不加</b> {@code @Transactional}：
 *       每行调用 {@link QuestionService#create} 时由那个方法自己开事务，因此第 7 行失败
 *       不会把前 6 行一起回滚。50 道题里错了 3 道，教师只需改这 3 道，而不是重来一遍；</li>
 *   <li><b>知识点必须已存在，不自动创建。</b>知识点名称全局唯一且是题库的分类骨架，
 *       表格里一个错别字就会留下「Java基础」和「Java 基楚」这样一对永久的孪生分类。
 *       报错里点名说明缺哪个知识点，教师在题库页上一步就能补；</li>
 *   <li><b>重复题干跳过而不是失败。</b>把同一个文件导入两次是最常见的误操作，
 *       它既不是成功也不是错误，单列一个「跳过」比塞进失败数里更容易看懂。
 *       判重可以在系统设置里关掉——同一题干配不同选项是合法出题手法。</li>
 * </ol>
 */
@Service
public class QuestionImportService {
    /** 分值缺省值，与 AI 出题保持一致，也与验收数据里的每题 10 分一致。 */
    private static final BigDecimal DEFAULT_SCORE = BigDecimal.TEN;
    private static final BigDecimal MIN_SCORE = new BigDecimal("0.1");
    private static final BigDecimal MAX_SCORE = new BigDecimal("99999.9");

    // 列名别名。每组的第一个是模板里用的名字，其余是常见写法；匹配时大小写和空白都会被忽略。
    private static final String[] COL_TYPE = {"题型", "type", "题目类型"};
    private static final String[] COL_STEM = {"题干", "stem", "题目", "题目内容", "内容"};
    private static final String[] COL_DIFFICULTY = {"难度", "difficulty"};
    private static final String[] COL_POINT = {"知识点", "knowledgePoint", "知识点名称", "章节"};
    private static final String[] COL_SCORE = {"分值", "score", "suggestedScore", "建议分值"};
    private static final String[] COL_OPTIONS = {"选项", "options", "选项内容"};
    private static final String[] COL_ANSWER = {"标准答案", "answer", "答案", "standardAnswer", "正确答案"};
    private static final String[] COL_EXPLANATION = {"解析", "explanation", "答案解析", "评分要点"};
    private static final String[] COL_TAGS = {"标签", "tags", "关键词"};

    /**
     * 选项号前缀，例如 {@code A. 内容}、{@code B、内容}、{@code C）内容}。
     *
     * <p>刻意不把逗号当分隔符：选项正文里出现逗号太常见，误判会把内容截掉一半。
     */
    private static final Pattern OPTION_KEY = Pattern.compile("^\\s*([A-Za-z0-9]{1,4})\\s*[.、．:：)）]\\s*(.*)$");

    /**
     * 导入模板正文。
     *
     * <p>模板和解析器放在同一个类里是为了防漂移：模板的表头必须是解析器认得的列名，
     * 每一格的取值必须是解析器认得的写法。两者分家之后，只要有人改了别名表，
     * 模板就会变成一份「下载下来照着填反而报错」的文件。
     * 自动化测试会把这份模板原样喂给导入接口，验证五行示例全部通过校验。
     */
    private static final String TEMPLATE = """
            题型,题干,难度,知识点,分值,选项,标准答案,解析,标签
            单选题,下列哪一项是 Java 的基本数据类型？,易,示例知识点,10,A. int|B. String|C. Integer|D. List,A,只有 int 是基本类型，其余三个都是引用类型。,"Java, 数据类型"
            多选题,下列哪些属于 List 接口的实现类？,中,示例知识点,10,A. ArrayList|B. LinkedList|C. HashMap|D. HashSet,AB,"多选题要求答案集合完全一致才得分，少选同样不得分。","Java, 集合"
            判断题,String 对象一旦创建其内容就不可修改。,易,示例知识点,10,,正确,String 内部保存字符的数组是 final 的。,Java
            简答题,"请简述 ArrayList 与 LinkedList 的区别，并各举一个适用场景。",中,示例知识点,10,,"ArrayList 基于数组，随机访问快；LinkedList 基于链表，插入删除快。","按要点给分：数据结构 4 分，性能差异 3 分，适用场景 3 分。","Java, 集合"
            编程题,"编写一个方法，统计字符串中每个字符出现的次数。",难,示例知识点,10,,"用 HashMap<Character, Integer> 逐字符累加计数，最后返回该 Map。",能正确处理空字符串即可得基本分。,"Java, 字符串"
            """;

    private final QuestionService questions;
    private final QuestionRepository repository;
    private final KnowledgePointRepository knowledgePoints;
    private final AnswerNormalizer answers;
    private final SettingsStore settings;

    public QuestionImportService(QuestionService questions, QuestionRepository repository,
            KnowledgePointRepository knowledgePoints, AnswerNormalizer answers, SettingsStore settings) {
        this.questions = questions; this.repository = repository;
        this.knowledgePoints = knowledgePoints; this.answers = answers; this.settings = settings;
    }

    /** 带 BOM 的模板正文。BOM 不能省：少了它 Excel 会把中文表头显示成乱码。 */
    public String template() { return "\uFEFF" + TEMPLATE; }

    /**
     * 执行导入或预览。
     *
     * <p>预览与导入是同一段代码，只差最后一步写不写库：两者用同一个解析器、同一套业务校验、
     * 同一套重复判定。这样「预览说能导入 18 道、实际只进了 12 道」这种事不会发生。
     *
     * <p>行数上限与是否跳过重复题干来自系统设置（{@link SettingsCatalog}），不是写死的常量：
     * 这两项都属于「改了立刻生效、改错也不会让系统不可用」的参数，因此允许管理员在界面上调整。
     *
     * @param request 表格正文与是否预览
     * @param userId  当前教师，既是题目归属也是重复判定的范围
     */
    public ImportResult run(ImportRequest request, long userId) {
        CsvTable table = CsvTable.parse(request.content());
        requireColumns(table);
        // 行数上限与「重复题干是否跳过」都取自系统设置，管理员改完即时生效（默认 200 行、跳过重复）。
        int maxRows = settings.asInt(SettingsCatalog.IMPORT_MAX_ROWS);
        if (table.size() > maxRows) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "IMPORT_TOO_MANY_ROWS",
                    "单次最多导入 " + maxRows + " 行，当前有 " + table.size() + " 行，请分批导入");
        }
        boolean skipDuplicate = settings.asBoolean(SettingsCatalog.IMPORT_SKIP_DUPLICATE);
        // 知识点一次全量取出建成「名称 → ID」，避免每行查一次库；名称按去空白小写匹配。
        Map<String, Long> pointsByName = new HashMap<>();
        knowledgePoints.findAll().forEach(point -> pointsByName.putIfAbsent(compact(point.name()), point.id()));

        boolean preview = request.preview();
        // 本次导入内部的题干去重集合。只查数据库不够：同一个文件里复制粘贴出两行相同题目也很常见，
        // 而预览阶段两行都还没入库，只查库会双双通过，随后正式导入却只成功一行——预览就不准了。
        Set<String> seenStems = new HashSet<>();
        List<RowResult> rows = new ArrayList<>();
        int imported = 0;
        int skipped = 0;
        int failed = 0;
        for (int index = 0; index < table.size(); index++) {
            int line = table.lineNumber(index);
            try {
                QuestionRequest draft = toRequest(table, index, pointsByName);
                // 与手工出题、AI 草稿完全相同的校验。放在重复判定之前：一道题既非法又重复时，
                // 「哪里写错了」比「已经有了」更有用。
                questions.validateDraft(draft);
                String stem = draft.stem().trim();
                String duplicate = skipDuplicate ? duplicateReason(stem, seenStems, userId) : null;
                if (duplicate != null) {
                    skipped++;
                    rows.add(new RowResult(line, Outcome.SKIPPED, draft.stem(), draft.type(), null, duplicate));
                    continue;
                }
                if (preview) {
                    imported++;
                    rows.add(new RowResult(line, Outcome.IMPORTED, draft.stem(), draft.type(), null, null));
                    continue;
                }
                QuestionView saved = questions.create(draft, userId);
                imported++;
                rows.add(new RowResult(line, Outcome.IMPORTED, saved.stem(), saved.type(), saved.id(), null));
            } catch (DomainException exception) {
                // 单行失败只记录不中断：整批失败会让教师为一格笔误重传整个文件。
                failed++;
                rows.add(new RowResult(line, Outcome.FAILED, table.cell(index, COL_STEM), null, null,
                        exception.getMessage()));
            }
        }
        return new ImportResult(preview, table.size(), imported, skipped, failed, rows);
    }

    /**
     * 检查必填列是否齐备。
     *
     * <p>整表缺列时直接整批失败，而不是让每一行都报同一句错：几十行重复的
     * 「题型不能为空」既掩盖了真正的原因，也让教师以为是数据问题而不是表头问题。
     */
    private void requireColumns(CsvTable table) {
        List<String> missing = new ArrayList<>();
        if (!table.has(COL_TYPE)) missing.add("题型");
        if (!table.has(COL_STEM)) missing.add("题干");
        if (!table.has(COL_POINT)) missing.add("知识点");
        if (!table.has(COL_ANSWER)) missing.add("标准答案");
        if (!missing.isEmpty()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "IMPORT_INVALID_FORMAT",
                    "表头缺少必填列：" + String.join("、", missing) + "。可先下载模板，按模板的表头填写");
        }
    }

    /**
     * 把一行表格映射成题目请求。
     *
     * <p>只做「文本 → 结构」的转换和取值范围检查，不重复实现业务规则：
     * 「答案是否引用了存在的选项」「单选题是否只有一个答案」仍然交给
     * {@link QuestionService#validateDraft} 判定。
     */
    private QuestionRequest toRequest(CsvTable table, int index, Map<String, Long> pointsByName) {
        Type type = parseType(table.cell(index, COL_TYPE));
        String stem = table.cell(index, COL_STEM);
        if (stem.isBlank()) throw invalid("题干不能为空");
        Difficulty difficulty = parseDifficulty(table.cell(index, COL_DIFFICULTY));
        long pointId = resolvePoint(table.cell(index, COL_POINT), pointsByName);
        BigDecimal score = parseScore(table.cell(index, COL_SCORE));
        // 非选择题一律不带选项：表格里顺手填了内容也忽略，否则会撞上「判断题不能包含选项」的校验。
        List<OptionRequest> options = isChoice(type) ? parseOptions(table.cell(index, COL_OPTIONS)) : List.of();
        JsonNode answer = parseAnswer(type, table.cell(index, COL_ANSWER));
        return new QuestionRequest(type, stem, difficulty, text(table.cell(index, COL_TAGS), 200),
                answer, text(table.cell(index, COL_EXPLANATION), 2000), score, pointId, options);
    }

    /** 判断题干是否重复，返回可读原因；不重复返回 null。同时把题干登记进本次导入的去重集合。 */
    private String duplicateReason(String stem, Set<String> seenStems, long userId) {
        if (!seenStems.add(stem)) return "本次导入的前面几行已有相同题干，本行跳过";
        if (repository.existsByStem(userId, stem)) return "题库中已存在相同题干的题目，本行跳过";
        return null;
    }

    /** 题型：中文名、简称和英文枚举名都认。 */
    private Type parseType(String raw) {
        return switch (compact(raw)) {
            case "单选题", "单选", "单项选择题", "单项选择", "single_choice", "singlechoice" -> Type.SINGLE_CHOICE;
            case "多选题", "多选", "多项选择题", "多项选择", "multiple_choice", "multiplechoice" -> Type.MULTIPLE_CHOICE;
            case "判断题", "判断", "对错题", "true_false", "truefalse" -> Type.TRUE_FALSE;
            case "简答题", "简答", "问答题", "问答", "short_answer", "shortanswer" -> Type.SHORT_ANSWER;
            case "编程题", "编程", "程序设计题", "programming" -> Type.PROGRAMMING;
            case "" -> throw invalid("题型不能为空");
            default -> throw invalid("无法识别的题型「" + raw.trim()
                    + "」，只能填 单选题 / 多选题 / 判断题 / 简答题 / 编程题");
        };
    }

    /** 难度：不填按中等处理，因为绝大多数题目都是中等难度，强制填写只会增加导入失败率。 */
    private Difficulty parseDifficulty(String raw) {
        return switch (compact(raw)) {
            case "", "中", "中等", "一般", "medium" -> Difficulty.MEDIUM;
            case "易", "简单", "容易", "低", "easy" -> Difficulty.EASY;
            case "难", "困难", "高", "hard" -> Difficulty.HARD;
            default -> throw invalid("无法识别的难度「" + raw.trim() + "」，只能填 易 / 中 / 难");
        };
    }

    /**
     * 分值：不填按 10 分，填了就必须是一位小数以内的正数。
     *
     * <p>超出一位小数直接报错而不是四舍五入：数据库列是 {@code DECIMAL(6,1)}，
     * 静默取整会让教师看到的分值和他填的不一样，而这属于「悄悄改了别人的数据」。
     */
    private BigDecimal parseScore(String raw) {
        if (raw.isBlank()) return DEFAULT_SCORE;
        BigDecimal score;
        try {
            score = new BigDecimal(raw.trim());
        } catch (NumberFormatException exception) {
            throw invalid("分值「" + raw.trim() + "」不是数字");
        }
        if (score.stripTrailingZeros().scale() > 1) throw invalid("分值最多保留一位小数");
        if (score.compareTo(MIN_SCORE) < 0) throw invalid("分值必须不小于 0.1");
        if (score.compareTo(MAX_SCORE) > 0) throw invalid("分值不能大于 99999.9");
        return score;
    }

    /**
     * 选项：一格里用「|」分隔，例如 {@code A. 甲|B. 乙|C. 丙|D. 丁}。
     *
     * <p>选项号可以省略：只写 {@code 甲|乙|丙|丁} 时按顺序补成 A、B、C、D。
     * 这一步是刻意做的——手工整理的表格常常只有选项正文，而补选项号是机械劳动。
     *
     * <p>是否带选项号以第一个选项为准，混写直接报错：{@code A. 甲|乙} 究竟是「乙的选项号是 B」
     * 还是「漏写了 B.」无法判断，猜错会让标准答案指向另一个选项。
     */
    private List<OptionRequest> parseOptions(String raw) {
        List<String> parts = splitOptions(raw);
        if (parts.isEmpty()) {
            throw invalid("选择题必须填写选项，用「|」分隔，例如：A. 甲|B. 乙|C. 丙|D. 丁");
        }
        if (parts.size() > 26) throw invalid("一道题的选项不能超过 26 个");
        boolean keyed = OPTION_KEY.matcher(parts.get(0)).matches();
        List<OptionRequest> options = new ArrayList<>();
        for (int index = 0; index < parts.size(); index++) {
            String key;
            String content;
            if (keyed) {
                Matcher matcher = OPTION_KEY.matcher(parts.get(index));
                if (!matcher.matches()) {
                    throw invalid("第 " + (index + 1) + " 个选项缺少「A.」这样的选项号；"
                            + "同一格里的选项要么都带选项号，要么都不带");
                }
                key = matcher.group(1).trim().toUpperCase(Locale.ROOT);
                content = matcher.group(2).trim();
            } else {
                // 没有选项号时按位置补 A、B、C……与教师在表格里看到的顺序一致。
                key = String.valueOf((char) ('A' + index));
                content = parts.get(index);
            }
            if (content.isEmpty()) throw invalid("第 " + (index + 1) + " 个选项没有内容");
            options.add(new OptionRequest(key, content));
        }
        return options;
    }

    /** 拆选项：优先按竖线，一格里写成多行时按换行。全角竖线一并支持，中文输入法下很容易打出。 */
    private List<String> splitOptions(String raw) {
        if (raw.isBlank()) return List.of();
        String[] parts = raw.indexOf('|') >= 0 || raw.indexOf('｜') >= 0 ? raw.split("[|｜]") : raw.split("\\R");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) result.add(part.trim());
        }
        return result;
    }

    /**
     * 标准答案：空值一律拒绝，判断题必须是认识的词。
     *
     * <p>判断题走 {@link AnswerNormalizer#parseBoolean} 的严格版本而不是宽松归一化。
     * 宽松版把不认识的词按「错误」处理，那是 AI 出题可以接受的取舍（教师会逐题看草稿）；
     * 导入是几十道一起进库，一个无法识别的值静默变成「错误」，等于给题库塞了一道答案相反的题。
     */
    private JsonNode parseAnswer(Type type, String raw) {
        if (raw.isBlank()) throw invalid("标准答案不能为空");
        if (type == Type.TRUE_FALSE && answers.parseBoolean(raw).isEmpty()) {
            throw invalid("判断题的标准答案「" + raw.trim() + "」无法识别，请填 正确 或 错误");
        }
        // 形态归一化与 AI 出题共用同一段实现：「AB」拆成 ["A","B"]、「正确」转成布尔 true。
        return answers.normalizeText(type, raw);
    }

    /** 知识点按名称匹配。不自动创建，理由见类注释第 3 条。 */
    private long resolvePoint(String raw, Map<String, Long> pointsByName) {
        String name = raw.trim();
        if (name.isEmpty()) throw invalid("知识点不能为空");
        Long id = pointsByName.get(compact(name));
        if (id == null) {
            throw invalid("知识点「" + name + "」不存在，请先在题库页添加这个知识点，再重新导入");
        }
        return id;
    }

    /** 选填文本：空白转 null，并按数据库列长度截断，避免保存时才报错。 */
    private String text(String raw, int maxLength) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return null;
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    /** 取值比较用的规范化：去掉全部空白再小写，让「Java 基础」和「java基础」视为同一个名字。 */
    private String compact(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("[\\s　]+", "").toLowerCase(Locale.ROOT);
    }

    private boolean isChoice(Type type) { return type == Type.SINGLE_CHOICE || type == Type.MULTIPLE_CHOICE; }

    /**
     * 单行失败统一用 400 与 {@code IMPORT_ROW_INVALID}。
     *
     * <p>注意这个异常大多不会直接返回给前端：它在 {@link #run} 的循环里被捕获，
     * 变成结果表里的一行。只有整表级别的失败（缺列、超行数、根本不是表格）才会真的返回 400。
     */
    private DomainException invalid(String message) {
        return new DomainException(HttpStatus.BAD_REQUEST, "IMPORT_ROW_INVALID", message);
    }
}
