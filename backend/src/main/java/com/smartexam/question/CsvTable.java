package com.smartexam.question;

import com.smartexam.common.DomainException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 极简 CSV / TSV 读取器，只服务于题库批量导入。
 *
 * <p>没有引入 commons-csv 之类的库，理由和项目里其他「未引入」的决定一致：本功能需要的
 * 只是「带引号转义的分隔文本 + 表头列名映射」，自己写不到一百行，而多一个依赖就多一处
 * 我在答辩时说不清来源的代码。反过来说，必须自己实现的部分一个都不能省——尤其是引号转义：
 * 题干里出现逗号几乎是必然的，按 {@code split(",")} 处理会把一道题拆成两列。
 *
 * <p>刻意处理的四个真实问题：
 * <ol>
 *   <li><b>UTF-8 BOM。</b>Excel 另存为 CSV 时会写入 BOM，不剥掉的话第一列表头永远匹配不上；</li>
 *   <li><b>分隔符探测。</b>从 Excel 直接复制粘贴得到的是制表符分隔，另存为文件才是逗号；</li>
 *   <li><b>引号内的分隔符与换行。</b>{@code "选项 A, 选项 B"} 是一格，不是两格；</li>
 *   <li><b>行号。</b>报错必须给出教师在 Excel 里看到的行号，因此记录每条记录的起始行，
 *       而不是用「第几条数据」代替。</li>
 * </ol>
 */
final class CsvTable {
    /** 表头列名 → 列下标。用规范化后的列名作键，保留插入顺序便于错误信息里回显。 */
    private final Map<String, Integer> columns;
    /** 数据行，不含表头。 */
    private final List<List<String>> rows;
    /** 每条数据行在原文里的起始行号，从 1 开始且包含表头行。 */
    private final List<Integer> lineNumbers;

    private CsvTable(Map<String, Integer> columns, List<List<String>> rows, List<Integer> lineNumbers) {
        this.columns = columns; this.rows = rows; this.lineNumbers = lineNumbers;
    }

    /**
     * 解析整段文本：第一行是表头，其余是数据行，全空行被跳过。
     *
     * @throws DomainException 内容为空或只有表头时抛出，让调用方直接得到可读错误
     */
    static CsvTable parse(String content) {
        String text = content == null ? "" : content.replace("\uFEFF", "");
        if (text.isBlank()) throw fail("导入内容为空");
        List<Integer> lines = new ArrayList<>();
        List<List<String>> records = split(text, delimiterOf(text), lines);
        // 去掉全空行：表格末尾多敲几个回车、中间空一行分组都很常见，不该算成失败的数据行。
        for (int index = records.size() - 1; index >= 0; index--) {
            if (records.get(index).stream().allMatch(field -> field == null || field.isBlank())) {
                records.remove(index); lines.remove(index);
            }
        }
        if (records.isEmpty()) throw fail("导入内容为空");
        Map<String, Integer> columns = new LinkedHashMap<>();
        List<String> header = records.get(0);
        for (int index = 0; index < header.size(); index++) {
            String name = normalizeHeader(header.get(index));
            // 同名列只认第一处：重复列名要么是复制粘贴留下的，要么是笔误，取第一处比报错更宽容。
            if (!name.isEmpty()) columns.putIfAbsent(name, index);
        }
        if (columns.isEmpty()) throw fail("第一行必须是表头，例如「题型,题干,难度,知识点,分值,选项,标准答案」");
        if (records.size() == 1) throw fail("只有表头没有数据行");
        return new CsvTable(columns, records.subList(1, records.size()), lines.subList(1, lines.size()));
    }

    /** 数据行数。 */
    int size() { return rows.size(); }

    /** 第 {@code index} 条数据在原文里的行号，用于错误提示。 */
    int lineNumber(int index) { return lineNumbers.get(index); }

    /** 表头是否包含指定列（任一别名命中即可）。 */
    boolean has(String... aliases) { return columnIndex(aliases) >= 0; }

    /**
     * 取一格内容，列不存在或该行列数不足时返回空串。
     *
     * <p>列数不足要当成空值而不是报错：表格末尾的空列常被编辑器省略，
     * 「解析」「标签」这些选填列缺失时不该让整行失败。
     */
    String cell(int index, String... aliases) {
        int column = columnIndex(aliases);
        if (column < 0) return "";
        List<String> row = rows.get(index);
        return column < row.size() ? row.get(column).trim() : "";
    }

    /** 按别名找列下标，找不到返回 -1。 */
    private int columnIndex(String... aliases) {
        for (String alias : aliases) {
            Integer column = columns.get(normalizeHeader(alias));
            if (column != null) return column;
        }
        return -1;
    }

    /**
     * 规范化列名：剥 BOM、去空白、去括号内的提示文字和星号，再统一小写。
     *
     * <p>这样模板里可以写成「难度（易/中/难）」「题干*」这种带提示的表头，
     * 教师照抄模板不会因为多了几个字就匹配不上。
     */
    private static String normalizeHeader(String raw) {
        return raw.replace("\uFEFF", "")
                .replaceAll("[（(].*?[)）]", "")
                .replace("*", "")
                .replaceAll("[\\s　]+", "")
                .toLowerCase(Locale.ROOT);
    }

    /**
     * 探测分隔符：在第一行里数逗号、制表符和分号，取出现次数最多的那个。
     *
     * <p>只看第一行是因为表头几乎不含引号和换行，数出来的次数可信；用整段文本反而会被
     * 题干里的逗号带偏。三者次数都为 0 时按逗号处理，此时全行会被当成一列，
     * 后续的必填列检查会给出「表头缺少 题型 列」这样的具体提示。
     */
    private static char delimiterOf(String text) {
        int end = text.indexOf('\n');
        String header = end < 0 ? text : text.substring(0, end);
        char best = ',';
        long bestCount = header.chars().filter(character -> character == ',').count();
        for (char candidate : new char[] {'\t', ';'}) {
            long count = header.chars().filter(character -> character == candidate).count();
            if (count > bestCount) { best = candidate; bestCount = count; }
        }
        return best;
    }

    /**
     * 逐字符扫描切分记录，按 RFC 4180 处理双引号。
     *
     * <p>用状态机而不是正则：正则处理不了「引号内的换行」这类跨行结构，
     * 而带换行的长题干在真实表格里很常见。
     *
     * @param lineNumbers 出参，逐条记下每条记录的起始行号
     */
    private static List<List<String>> split(String text, char delimiter, List<Integer> lineNumbers) {
        List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean started = false;
        int line = 1;
        int recordLine = 1;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (!started) { recordLine = line; started = true; }
            if (quoted) {
                if (character == '"') {
                    // 连续两个引号是一个转义的引号本身，单个引号则表示引用段结束。
                    if (index + 1 < text.length() && text.charAt(index + 1) == '"') { field.append('"'); index++; }
                    else quoted = false;
                } else {
                    if (character == '\n') line++;
                    field.append(character);
                }
                continue;
            }
            if (character == '"') { quoted = true; continue; }
            if (character == delimiter) { current.add(field.toString()); field.setLength(0); continue; }
            if (character == '\r') continue;
            if (character == '\n') {
                line++;
                current.add(field.toString()); field.setLength(0);
                records.add(current); lineNumbers.add(recordLine);
                current = new ArrayList<>(); started = false;
                continue;
            }
            field.append(character);
        }
        // 最后一行没有换行结尾时补一条；已经因换行收尾时这里的条件全部为假，不会多出空记录。
        if (started || field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            records.add(current); lineNumbers.add(recordLine);
        }
        return records;
    }

    /** 解析阶段的失败统一用 400 与 {@code IMPORT_INVALID_FORMAT}：请求格式问题，不是服务端故障。 */
    private static DomainException fail(String message) {
        return new DomainException(HttpStatus.BAD_REQUEST, "IMPORT_INVALID_FORMAT", message);
    }
}
