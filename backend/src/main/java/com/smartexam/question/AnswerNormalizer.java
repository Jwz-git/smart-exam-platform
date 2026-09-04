package com.smartexam.question;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.smartexam.question.QuestionModels.Type;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 标准答案的形态归一化，被 AI 辅助出题和题库批量导入共用。
 *
 * <p>抽成独立组件而不是各自写一份，原因是两个入口面对的是同一类问题：拿到的答案是
 * 「人或模型随手写的形态」，而不是数据库要求的形态。多选题写成 {@code "AB"}、判断题写成
 * 「正确」都属于常见写法，把它们掰成 {@code ["A","B"]} 和布尔 {@code true} 是安全的，
 * 因为这一步<b>只改形态、不改内容</b>。
 *
 * <p>关键边界：正确性判断一律不在这里做。「答案是否引用了存在的选项」「单选题是否只有一个答案」
 * 仍然由 {@link QuestionService#validateDraft} 判定。归一化失败的值原样交给校验去拒绝，
 * 不在本类里抛错——否则同一个错误会有两处不同的措辞。
 */
@Component
public class AnswerNormalizer {
    /**
     * 判断题里表示「是」的全部写法。
     *
     * <p>只维护「真」的词表，其余一律按假处理或交由调用方拒绝：真假两套词表都写，
     * 一旦出现漏词就会有值同时落在两边。
     */
    private static final Set<String> TRUE_WORDS =
            Set.of("true", "1", "t", "y", "yes", "正确", "对", "是", "√", "✓");
    /** 判断题里表示「否」的写法，只用于「这个词到底认不认识」的判断，不参与取值。 */
    private static final Set<String> FALSE_WORDS =
            Set.of("false", "0", "f", "n", "no", "错误", "错", "否", "×", "✗");

    private final ObjectMapper json;

    public AnswerNormalizer(ObjectMapper json) { this.json = json; }

    /**
     * 把一个来源不可控的答案节点掰成题型要求的形态。
     *
     * <p>这是「宽松」策略：判断题遇到无法识别的词按 {@code false} 处理。AI 出题走这条路——
     * 模型偶尔写出无意义的答案，整批失败对教师没有好处，让校验或教师自己发现更合适。
     * 批量导入需要的是「不认识就报错」，走 {@link #parseBoolean(String)}。
     */
    public JsonNode normalize(Type type, JsonNode answer) {
        return switch (type) {
            case SINGLE_CHOICE, MULTIPLE_CHOICE -> optionKeys(answer);
            case TRUE_FALSE -> {
                if (answer.isBoolean()) yield answer;
                yield json.getNodeFactory().booleanNode(parseBoolean(answer.asText("")).orElse(false));
            }
            // 简答题与编程题：非文本节点按原样序列化成文本，保证存进库的一定是字符串。
            case SHORT_ANSWER, PROGRAMMING -> json.getNodeFactory()
                    .textNode(answer.isTextual() ? answer.asText().trim() : answer.toString());
        };
    }

    /** 文本入口，供 CSV 这类「所有单元格都是字符串」的来源使用。 */
    public JsonNode normalizeText(Type type, String raw) {
        return normalize(type, json.getNodeFactory().textNode(raw == null ? "" : raw));
    }

    /**
     * 严格解析判断题答案：认识就返回真假，不认识返回空。
     *
     * <p>批量导入必须用这个版本。如果把「香蕉」这类无法识别的值静默当成「错误」，
     * 教师会得到一道答案键值错误的题，而错误只会在学生答完之后暴露出来——
     * 那时已经影响到成绩了。宁可在导入时报错，让他改一格。
     */
    public Optional<Boolean> parseBoolean(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (TRUE_WORDS.contains(value)) return Optional.of(true);
        if (FALSE_WORDS.contains(value)) return Optional.of(false);
        return Optional.empty();
    }

    /**
     * 把选择题答案掰成大写选项键数组。
     *
     * <p>三种来源形态都兼容：已经是数组、写成 {@code "A,B"}、写成 {@code "AB"}。
     * 后两种在真实数据里都很常见——手工维护的表格几乎不会写成 JSON 数组。
     */
    private ArrayNode optionKeys(JsonNode answer) {
        ArrayNode keys = json.createArrayNode();
        if (answer.isArray()) {
            answer.forEach(node -> keys.add(node.asText("").trim().toUpperCase(Locale.ROOT)));
            return keys;
        }
        if (!answer.isTextual()) return keys;
        String raw = answer.asText().trim().toUpperCase(Locale.ROOT);
        for (String part : raw.split("[,，、;；/\\s]+")) {
            if (part.isBlank()) continue;
            // 单个字符按一个键处理；「AB」这类连写按字符逐个拆开。
            if (part.length() == 1) keys.add(part);
            else part.chars().forEach(character -> keys.add(String.valueOf((char) character)));
        }
        return keys;
    }
}
