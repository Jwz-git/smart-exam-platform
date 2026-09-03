package com.smartexam.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.smartexam.ai.AiModels.DraftRequest;
import com.smartexam.ai.AiModels.DraftResponse;
import com.smartexam.common.DomainException;
import com.smartexam.question.QuestionModels.OptionRequest;
import com.smartexam.question.QuestionModels.QuestionRequest;
import com.smartexam.question.QuestionModels.Type;
import com.smartexam.question.QuestionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * AI 辅助出题：拼提示词 → 调模型 → 解析 JSON → 归一化 → 走现有业务校验 → 返回草稿。
 *
 * <p>四条边界必须同时成立，否则 AI 就从「助手」变成了「绕过校验的后门」：
 * <ol>
 *   <li><b>只生成草稿，不入库。</b>本类没有任何写库操作。教师在界面上编辑确认后，
 *       仍然走 {@code POST /api/v1/questions} 保存，与手工出题走同一条路径；</li>
 *   <li><b>题型、难度、知识点和分值由教师指定，不由模型决定。</b>模型只创作题干、选项、答案和解析；</li>
 *   <li><b>模型输出必须通过 {@link QuestionService#validateDraft(QuestionRequest)} 的完整校验。</b>
 *       不合规的草稿被丢弃并在 {@code warnings} 里说明原因，不会悄悄放行；</li>
 *   <li><b>失败不能阻塞主流程。</b>未配置密钥、超时、空内容、非法 JSON 都只影响这一个接口。</li>
 * </ol>
 */
@Service
public class AiService {
    /**
     * 系统提示。三条硬要求：只输出 JSON、字段名固定、选项键用大写字母。
     *
     * <p>「不要输出 Markdown 代码块」写了但不指望模型一定听话——{@link #extractJson} 会兜底剥掉围栏。
     * 提示词是尽力而为，解析必须容错，两者缺一不可。
     */
    private static final String SYSTEM_PROMPT = """
            你是一名严谨的计算机课程命题教师。请严格按要求生成考试题目。
            只输出一个 JSON 对象，不要输出解释文字，也不要用 Markdown 代码块包裹。
            JSON 结构固定为：{"questions":[{"stem":"题干","options":[{"key":"A","content":"选项内容"}],"standardAnswer":<答案>,"explanation":"解析","tags":"关键词1, 关键词2"}]}
            规则：
            1. 单选题和多选题必须给出至少 4 个选项，选项键用大写字母 A、B、C、D，standardAnswer 是选项键数组；单选题数组长度为 1，多选题至少 2 个。
            2. 判断题不要 options 字段，standardAnswer 是布尔值 true 或 false。
            3. 简答题和编程题不要 options 字段，standardAnswer 是参考答案文本。
            4. explanation 写评分要点或解题思路。tags 用英文逗号分隔的 2-4 个关键词。
            5. 题干不要包含题号、分值和答案。
            """;

    private final AiClient client;
    private final AiProperties properties;
    private final QuestionService questions;
    private final ObjectMapper json;

    public AiService(AiClient client, AiProperties properties, QuestionService questions, ObjectMapper json) {
        this.client = client; this.properties = properties; this.questions = questions; this.json = json;
    }

    /**
     * 生成题目草稿。
     *
     * <p>逐条校验而不是整批失败：模型生成 3 道题时可能只有 1 道不合规，
     * 因整批作废让教师重试一次并不划算。全部不合规才报错，并把第一条原因带出去。
     */
    public DraftResponse generate(DraftRequest request) {
        String text = client.complete(SYSTEM_PROMPT, userPrompt(request));
        if (text == null || text.isBlank()) {
            throw fail("AI_EMPTY_RESPONSE", "AI 未返回内容，请重试或改写补充要求");
        }
        JsonNode root = parse(text);
        List<QuestionRequest> drafts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<JsonNode> items = itemsOf(root);
        for (int index = 0; index < items.size(); index++) {
            QuestionRequest draft = toDraft(items.get(index), request);
            try {
                questions.validateDraft(draft);
                drafts.add(draft);
            } catch (DomainException exception) {
                // 如实记录被丢弃的草稿：教师需要知道「要了 3 道只回来 2 道」以及为什么。
                warnings.add("第 " + (index + 1) + " 道草稿未通过校验：" + exception.getMessage());
            }
        }
        if (drafts.isEmpty()) {
            throw fail("AI_DRAFT_INVALID", warnings.isEmpty()
                    ? "AI 返回的内容里没有可用题目，请重试" : "AI 生成的题目均未通过校验：" + warnings.get(0));
        }
        return new DraftResponse(properties.anthropic() ? "anthropic" : "openai", properties.model(), drafts, warnings);
    }

    /** 拼本次出题的具体要求。知识点名称由教师传入的 ID 反查，避免模型跑题到别的知识点。 */
    private String userPrompt(DraftRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("知识点：").append(questions.knowledgePointName(request.knowledgePointId()))
                .append("\n题型：").append(typeName(request.type()))
                .append("\n难度：").append(difficultyName(request.difficulty()))
                .append("\n数量：").append(request.normalizedCount()).append(" 道");
        if (request.requirement() != null && !request.requirement().isBlank()) {
            prompt.append("\n补充要求：").append(request.requirement().trim());
        }
        return prompt.toString();
    }

    /**
     * 从模型输出里解析 JSON。
     *
     * <p>先按原文解析，失败再退回「截取第一个 {@code {} 到最后一个 }} 之间的片段」。
     * 这一步是必要的容错：模型很常在 JSON 前后加一句「好的，这是生成的题目：」或套上
     * ```json 围栏，直接 readTree 会失败，而内容本身其实是好的。
     */
    private JsonNode parse(String text) {
        try {
            return json.readTree(text.trim());
        } catch (Exception ignored) {
            String extracted = extractJson(text);
            if (extracted == null) throw fail("AI_INVALID_JSON", "AI 返回的内容不是合法 JSON，请重试");
            try {
                return json.readTree(extracted);
            } catch (Exception exception) {
                throw fail("AI_INVALID_JSON", "AI 返回的内容不是合法 JSON，请重试");
            }
        }
    }

    /** 截出文本里最外层的 JSON 对象或数组；找不到成对的括号时返回 null。 */
    private String extractJson(String text) {
        int objectStart = text.indexOf('{');
        int objectEnd = text.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) return text.substring(objectStart, objectEnd + 1);
        int arrayStart = text.indexOf('[');
        int arrayEnd = text.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) return text.substring(arrayStart, arrayEnd + 1);
        return null;
    }

    /**
     * 取出题目数组。
     *
     * <p>兼容三种形状：{@code {"questions":[...]}}（提示词要求的）、裸数组 {@code [...]}、
     * 以及单个题目对象。模型在这三者之间摇摆很常见，容错的成本远低于让教师重试。
     */
    private List<JsonNode> itemsOf(JsonNode root) {
        JsonNode array = root.isArray() ? root : root.path("questions");
        if (array.isArray() && !array.isEmpty()) {
            List<JsonNode> items = new ArrayList<>();
            array.forEach(items::add);
            return items;
        }
        if (root.isObject() && root.hasNonNull("stem")) return List.of(root);
        throw fail("AI_INVALID_JSON", "AI 返回的 JSON 里没有 questions 数组，请重试");
    }

    /**
     * 把模型输出的一道题映射成题目请求。
     *
     * <p>题型、难度、知识点和分值一律用教师指定的值覆盖模型的输出：这四项决定题目能否通过校验，
     * 交给模型只会凭空增加失败率。模型的贡献仅限题干、选项、答案和解析。
     */
    private QuestionRequest toDraft(JsonNode item, DraftRequest request) {
        List<OptionRequest> options = new ArrayList<>();
        if (isChoice(request.type())) {
            item.path("options").forEach(option -> {
                String key = option.path("key").asText("").trim().toUpperCase(Locale.ROOT);
                String content = option.path("content").asText("").trim();
                if (!key.isEmpty() && !content.isEmpty()) options.add(new OptionRequest(key, content));
            });
        }
        return new QuestionRequest(request.type(), item.path("stem").asText("").trim(), request.difficulty(),
                text(item, "tags", 200), normalizeAnswer(request.type(), item.path("standardAnswer")),
                text(item, "explanation", 2000), request.normalizedScore(), request.knowledgePointId(), options);
    }

    /**
     * 把模型给的答案掰成题型要求的形态。
     *
     * <p>只做形态归一化，不做正确性判断——「A」变成 {@code ["A"]}、字符串 "true" 变成布尔 true
     * 这类修正是安全的，而「答案是否引用了存在的选项」「单选是否只有一个答案」仍然交给
     * {@link QuestionService#validateDraft(QuestionRequest)} 判定。归一化失败的留给校验去拒绝，不在这里抛错。
     */
    private JsonNode normalizeAnswer(Type type, JsonNode answer) {
        return switch (type) {
            case SINGLE_CHOICE, MULTIPLE_CHOICE -> {
                ArrayNode keys = json.createArrayNode();
                if (answer.isArray()) answer.forEach(node -> keys.add(node.asText("").trim().toUpperCase(Locale.ROOT)));
                else if (answer.isTextual()) {
                    // "A,B" 和 "AB" 两种写法都出现过，统一按分隔符和单字母拆开。
                    String raw = answer.asText().trim().toUpperCase(Locale.ROOT);
                    for (String part : raw.split("[,，、\\s]+")) {
                        if (part.isBlank()) continue;
                        if (part.length() == 1) keys.add(part);
                        else part.chars().forEach(character -> keys.add(String.valueOf((char) character)));
                    }
                }
                yield keys;
            }
            case TRUE_FALSE -> {
                if (answer.isBoolean()) yield answer;
                String raw = answer.asText("").trim().toLowerCase(Locale.ROOT);
                yield json.getNodeFactory().booleanNode("true".equals(raw) || "正确".equals(raw) || "对".equals(raw));
            }
            // 简答题与编程题：非文本节点按原样序列化成文本，保证一定是字符串。
            case SHORT_ANSWER, PROGRAMMING -> json.getNodeFactory()
                    .textNode(answer.isTextual() ? answer.asText().trim() : answer.toString());
        };
    }

    /** 读取可选文本字段并按数据库列长度截断，避免模型写太长导致保存时才失败。 */
    private String text(JsonNode item, String field, int maxLength) {
        String value = item.path(field).asText("").trim();
        if (value.isEmpty()) return null;
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private boolean isChoice(Type type) { return type == Type.SINGLE_CHOICE || type == Type.MULTIPLE_CHOICE; }

    /** 题型中文名，写进提示词比英文枚举名更容易让模型理解。 */
    private String typeName(Type type) {
        return switch (type) {
            case SINGLE_CHOICE -> "单选题";
            case MULTIPLE_CHOICE -> "多选题";
            case TRUE_FALSE -> "判断题";
            case SHORT_ANSWER -> "简答题";
            case PROGRAMMING -> "编程题";
        };
    }

    /** 难度中文名。 */
    private String difficultyName(com.smartexam.question.QuestionModels.Difficulty difficulty) {
        return switch (difficulty) { case EASY -> "简单"; case MEDIUM -> "中等"; case HARD -> "困难"; };
    }

    /** AI 相关失败统一用 502：请求本身没问题，是上游服务或其输出不可用。 */
    private DomainException fail(String code, String message) {
        return new DomainException(HttpStatus.BAD_GATEWAY, code, message);
    }
}
