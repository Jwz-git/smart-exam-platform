package com.smartexam.exam;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartexam.common.DomainException;
import com.smartexam.exam.ExamModels.PaperView;
import com.smartexam.exam.ExamRepository.PaperExportRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 试卷导出：把一份试卷导成可打印的 Markdown，或导成能被本系统题库导入接口直接读回去的 CSV。
 *
 * <p>两种格式各有明确用途，不是「同一份内容的两种皮肤」：
 * <ul>
 *   <li><b>Markdown（{@code md}）</b>——给人看的卷子。按大题分组、带中文序号和分值，
 *       {@code withAnswers=false} 时不含任何答案，可以直接发给学生；
 *       {@code withAnswers=true} 时在卷末附「参考答案与解析」，用于教师留存或线下阅卷；</li>
 *   <li><b>CSV（{@code csv}）</b>——给系统看的题目交换文件。列名与顺序<b>完全等于题库导入模板</b>，
 *       因此导出的文件可以原样喂给 {@code POST /questions/import}：一份试卷能被搬到另一套环境，
 *       也能把一份历史试卷的题目回灌进题库。既然导入接口要求「标准答案」列必填，
 *       CSV 就一定带答案，{@code withAnswers} 对它不起作用——这一点在接口文档里写明。</li>
 * </ul>
 *
 * <p>导出的内容来自试卷<b>快照</b>（题干、选项、答案、解析、分值），因此与当时发布的卷子完全一致，
 * 之后题库怎么改都不影响。只有难度、知识点和标签三列快照里没有，取的是题库当前值——
 * 这是刻意的取舍，因为 CSV 的「知识点」列是导入时的必填项，没有它导出的文件就无法回灌。
 */
@Service
public class PaperExportService {
    /** 导入模板的表头。与 {@code QuestionImportService} 的模板逐字一致，否则导出的文件导不回去。 */
    private static final String CSV_HEADER = "题型,题干,难度,知识点,分值,选项,标准答案,解析,标签";
    /** 大题序号。超过八个大题时退回阿拉伯数字，不至于越界。 */
    private static final String[] NUMERALS = {"一", "二", "三", "四", "五", "六", "七", "八"};
    /** 导出时间戳格式。带时区偏移，避免导出的文件看不出是哪个时区的时间。 */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (XXX)", Locale.SIMPLIFIED_CHINESE);

    private final ExamService exams;
    private final ExamRepository repository;

    public PaperExportService(ExamService exams, ExamRepository repository) {
        this.exams = exams; this.repository = repository;
    }

    /** 导出格式。 */
    public enum Format {
        MARKDOWN("md", "text/markdown;charset=UTF-8"),
        CSV("csv", "text/csv;charset=UTF-8");

        private final String extension;
        private final String contentType;

        Format(String extension, String contentType) {
            this.extension = extension; this.contentType = contentType;
        }

        /** 解析查询参数。大小写不敏感，{@code markdown} 与 {@code md} 等价。 */
        static Format of(String raw) {
            String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "", "md", "markdown" -> MARKDOWN;
                case "csv", "tsv" -> CSV;
                default -> throw new DomainException(HttpStatus.BAD_REQUEST, "EXPORT_FORMAT_UNSUPPORTED",
                        "不支持的导出格式「" + raw + "」，可用 md（可打印试卷）或 csv（可回导题库）");
            };
        }
    }

    /**
     * 导出一份试卷。
     *
     * <p>第一行就调 {@link ExamService#paper}：试卷不存在返回 404、不是本人的返回 403，
     * 归属校验因此只有一处实现，导出不会成为一条能读到别人试卷的旁路。
     *
     * @param withAnswers 是否附答案与解析；对 CSV 无效（CSV 必然含答案）
     */
    public Export export(long paperId, String format, boolean withAnswers, long teacherId) {
        PaperView paper = exams.paper(paperId, teacherId);
        List<PaperExportRow> rows = repository.findPaperExportRows(paperId);
        Format target = Format.of(format);
        String content = target == Format.CSV ? csv(rows) : markdown(paper, rows, withAnswers);
        return new Export(fileName(paper, target), target.contentType, content);
    }

    /**
     * 组装 Markdown 试卷。
     *
     * <p>按「连续同题型合并为一个大题」分组，与学生答题页的大题标题算法一致：
     * 教师导出的卷子和学生在屏幕上看到的分组必须是同一套，否则对着题号讲评会错位。
     */
    private String markdown(PaperView paper, List<PaperExportRow> rows, boolean withAnswers) {
        StringBuilder text = new StringBuilder();
        text.append("# ").append(paper.name()).append("\n\n");
        text.append("> 总分 ").append(plain(paper.totalScore())).append(" 分").append(" ｜ 考试时长 ")
                .append(paper.durationMinutes()).append(" 分钟").append(" ｜ 共 ").append(rows.size())
                .append(" 题 ｜ 试卷状态 ").append("PUBLISHED".equals(paper.status()) ? "已发布" : "草稿")
                .append("\n>\n> 导出时间 ").append(STAMP.format(Instant.now().atZone(ZoneId.systemDefault())))
                .append(withAnswers ? " ｜ 含参考答案" : " ｜ 不含答案，可直接发给学生").append("\n\n");
        text.append("---\n\n");
        if (rows.isEmpty()) {
            text.append("（这份试卷还没有题目）\n");
            return text.toString();
        }
        List<Group> groups = groups(rows);
        for (int index = 0; index < groups.size(); index++) {
            Group group = groups.get(index);
            text.append("## ").append(index < NUMERALS.length ? NUMERALS[index] : String.valueOf(index + 1))
                    .append("、").append(typeLabel(group.type())).append("（本大题共 ").append(group.rows().size())
                    .append(" 小题，").append(group.uniformScore() == null ? ""
                            : "每小题 " + plain(group.uniformScore()) + " 分，")
                    .append("共 ").append(plain(group.total())).append(" 分）\n\n");
            for (PaperExportRow row : group.rows()) {
                text.append("**").append(row.displayOrder()).append(".** ").append(oneLine(row.stem()))
                        .append("（").append(plain(row.score())).append(" 分）\n\n");
                for (Option option : options(row)) {
                    text.append("- ").append(option.key()).append(". ").append(oneLine(option.content())).append('\n');
                }
                if (!options(row).isEmpty()) text.append('\n');
                // 判断题不列选项，但要给出作答形式，否则打印出来的卷子上学生不知道该写什么。
                if ("TRUE_FALSE".equals(row.type())) text.append("- 正确 / 错误\n\n");
            }
        }
        if (withAnswers) {
            text.append("---\n\n## 参考答案与解析\n\n");
            for (PaperExportRow row : rows) {
                text.append(row.displayOrder()).append(". **").append(answerText(row))
                        .append("**（").append(plain(row.score())).append(" 分）\n");
                if (row.explanation() != null && !row.explanation().isBlank()) {
                    text.append("   - 解析：").append(oneLine(row.explanation())).append('\n');
                }
            }
            text.append("\n> 简答题与编程题的「答案」是评分参考，不是唯一答案，仍需教师人工评分。\n");
        }
        return text.toString();
    }

    /**
     * 组装可回导题库的 CSV。
     *
     * <p>带 UTF-8 BOM：与导入模板一致，否则 Excel 打开中文表头是乱码。
     * 分值一列写的是<b>本试卷里的分值</b>而不是题库建议分值——导出的是这份卷子，
     * 回导之后这个分值会成为题库里的建议分值，这比写回一个可能早已改过的旧建议分值更有意义。
     */
    private String csv(List<PaperExportRow> rows) {
        StringBuilder text = new StringBuilder("﻿").append(CSV_HEADER).append('\n');
        for (PaperExportRow row : rows) {
            List<String> options = new ArrayList<>();
            for (Option option : options(row)) options.add(option.key() + ". " + option.content());
            text.append(String.join(",", List.of(
                    cell(typeLabel(row.type())),
                    cell(row.stem()),
                    cell(difficultyLabel(row.difficulty())),
                    cell(row.knowledgePoint() == null ? "" : row.knowledgePoint()),
                    cell(plain(row.score())),
                    cell(String.join("|", options)),
                    cell(answerCell(row)),
                    cell(row.explanation() == null ? "" : row.explanation()),
                    cell(row.tags() == null ? "" : row.tags())))).append('\n');
        }
        return text.toString();
    }

    /**
     * 按 RFC 4180 转义一格。
     *
     * <p>题干里出现逗号、引号和换行都是常态，不转义会让导出的文件在导回时错列——
     * 这与导入侧的解析器是一对，两边必须用同一套规则。
     */
    private String cell(String value) {
        String text = value == null ? "" : value;
        if (text.indexOf(',') < 0 && text.indexOf('"') < 0 && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    /** 标准答案在 CSV 里的写法，必须是导入侧认得的形态：{@code AB} / 正确 / 参考文本。 */
    private String answerCell(PaperExportRow row) {
        JsonNode answer = row.answer();
        if (answer == null || answer.isNull()) return "";
        if (answer.isArray()) {
            StringBuilder keys = new StringBuilder();
            answer.forEach(node -> keys.append(node.asText("").trim().toUpperCase(Locale.ROOT)));
            return keys.toString();
        }
        if (answer.isBoolean()) return answer.asBoolean() ? "正确" : "错误";
        return answer.asText("");
    }

    /** 答案在 Markdown 里的写法，比 CSV 版本多一句「答案：」前缀，读起来更像一份答案页。 */
    private String answerText(PaperExportRow row) {
        String value = answerCell(row);
        return value.isBlank() ? "答案：（未设置）" : "答案：" + oneLine(value);
    }

    /** 解析选项快照。非选择题的快照是空数组，因此这里返回空列表即可。 */
    private List<Option> options(PaperExportRow row) {
        List<Option> options = new ArrayList<>();
        JsonNode node = row.options();
        if (node == null || !node.isArray()) return options;
        node.forEach(item -> options.add(new Option(item.path("key").asText(""), item.path("content").asText(""))));
        return options;
    }

    /** 把多行文本压成一行：Markdown 的列表项和 CSV 的一格都不适合出现裸换行。 */
    private String oneLine(String value) {
        return value == null ? "" : value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim();
    }

    /** 把连续同题型的题目合并成一个大题。 */
    private List<Group> groups(List<PaperExportRow> rows) {
        List<Group> groups = new ArrayList<>();
        for (PaperExportRow row : rows) {
            Group last = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            if (last != null && last.type().equals(row.type())) last.rows().add(row);
            else {
                List<PaperExportRow> items = new ArrayList<>();
                items.add(row);
                groups.add(new Group(row.type(), items));
            }
        }
        return groups;
    }

    /** 一个大题：连续的同题型题目。 */
    private record Group(String type, List<PaperExportRow> rows) {
        /** 大题分值合计。 */
        BigDecimal total() {
            return rows.stream().map(PaperExportRow::score).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** 每小题分值相同时返回该分值，否则返回 {@code null}，标题里就不写「每小题 x 分」。 */
        BigDecimal uniformScore() {
            BigDecimal first = rows.get(0).score();
            return rows.stream().allMatch(row -> row.score().compareTo(first) == 0) ? first : null;
        }
    }

    /** 选项键与正文。 */
    private record Option(String key, String content) {}

    /**
     * 拼下载文件名。
     *
     * <p>去掉文件系统与 HTTP 头里会出问题的字符（路径分隔符、引号、控制字符），
     * 名称为空时退回 {@code paper-{id}}。中文本身保留，由
     * {@code ContentDisposition} 按 RFC 5987 编码。
     */
    private String fileName(PaperView paper, Format format) {
        String base = paper.name() == null ? "" : paper.name().replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "").trim();
        if (base.length() > 60) base = base.substring(0, 60);
        if (base.isBlank()) base = "paper";
        return base + "-" + paper.id() + "." + format.extension;
    }

    /** 题型中文名。 */
    private String typeLabel(String type) {
        return switch (type == null ? "" : type) {
            case "SINGLE_CHOICE" -> "单选题";
            case "MULTIPLE_CHOICE" -> "多选题";
            case "TRUE_FALSE" -> "判断题";
            case "SHORT_ANSWER" -> "简答题";
            case "PROGRAMMING" -> "编程题";
            default -> type == null ? "" : type;
        };
    }

    /** 难度中文名；题库里查不到时留空，导入侧会按「中」处理。 */
    private String difficultyLabel(String difficulty) {
        return switch (difficulty == null ? "" : difficulty) {
            case "EASY" -> "易";
            case "MEDIUM" -> "中";
            case "HARD" -> "难";
            default -> "";
        };
    }

    /** 去掉多余的小数尾零，让 {@code 10.0} 显示成 {@code 10}。 */
    private String plain(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    /**
     * 导出结果。
     *
     * @param fileName    建议的下载文件名，含扩展名
     * @param contentType 响应的 Content-Type，已带 charset
     * @param content     文件正文
     */
    public record Export(String fileName, String contentType, String content) {}
}
