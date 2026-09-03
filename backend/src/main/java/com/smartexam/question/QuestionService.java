package com.smartexam.question;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartexam.common.DomainException;
import com.smartexam.common.PageResult;
import com.smartexam.question.QuestionModels.*;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 题库业务规则：资源归属校验、题型与标准答案一致性校验、删除降级为停用。
 *
 * <p>三条贯穿本类的规则：
 * <ol>
 *   <li>教师只能看到和操作自己创建的题目，越权返回 403 而不是 404；</li>
 *   <li>标准答案的形态必须和题型匹配，且选择题答案只能引用已存在的选项键；</li>
 *   <li>已被试卷引用的题目不做物理删除，改为停用，保证历史答卷可追溯。</li>
 * </ol>
 */
@Service
public class QuestionService {
    private final QuestionRepository repository;
    private final KnowledgePointRepository knowledgePoints;

    public QuestionService(QuestionRepository repository, KnowledgePointRepository knowledgePoints) {
        this.repository = repository; this.knowledgePoints = knowledgePoints;
    }

    /**
     * 对一份尚未保存的题目做完整业务校验。
     *
     * <p>供 AI 辅助出题复用：模型生成的草稿必须通过与手工出题完全相同的规则，
     * 才允许出现在教师的确认界面上。共用同一个 {@link #validate} 而不是复制一份，
     * 是为了避免出现「手工出题拒绝、AI 出题放行」这种两套口径。
     *
     * @throws com.smartexam.common.DomainException 校验不通过时抛出，携带具体原因
     */
    public void validateDraft(QuestionRequest request) { validate(request); }

    /** 取知识点名称，用于拼 AI 提示词；不存在时返回空字符串，由后续校验给出可读错误。 */
    public String knowledgePointName(long id) {
        return knowledgePoints.findById(id).map(KnowledgePointRepository.KnowledgePoint::name).orElse("");
    }

    /** 分页查询，固定按创建者过滤，教师之间的题库互不可见。 */
    public PageResult<QuestionView> list(long userId, String keyword, Type type, Difficulty difficulty,
            Long knowledgePointId, String status, int page, int size) {
        return repository.findAll(userId, keyword, type, difficulty, knowledgePointId, status, page, size);
    }

    /** 查询详情，顺带完成归属校验。 */
    public QuestionView get(long id, long userId) { return requireOwner(id, userId); }

    /**
     * 新增题目。
     *
     * <p>题目和选项分两张表写入，因此必须在同一事务内：否则中途失败会留下一道没有选项的题。
     * 选项键的唯一约束由数据库保证，冲突时转成 409。
     */
    @Transactional
    public QuestionView create(QuestionRequest request, long userId) {
        validate(request);
        try {
            long id = repository.create(request, userId);
            return repository.findById(id).orElseThrow();
        } catch (DataIntegrityViolationException exception) {
            throw new DomainException(HttpStatus.CONFLICT, "QUESTION_CONFLICT", "题目选项键重复或数据发生冲突");
        }
    }

    /** 编辑题目。选项采用先删后插的整体替换方式，避免逐个比对新增、修改、删除三种情况。 */
    @Transactional
    public QuestionView update(long id, QuestionRequest request, long userId) {
        requireOwner(id, userId);
        validate(request);
        try {
            repository.update(id, request);
            return repository.findById(id).orElseThrow();
        } catch (DataIntegrityViolationException exception) {
            throw new DomainException(HttpStatus.CONFLICT, "QUESTION_CONFLICT", "题目选项键重复或数据发生冲突");
        }
    }

    /** 启用或停用题目。停用题目保留历史试卷快照，但不能再加入新试卷。 */
    @Transactional
    public QuestionView setStatus(long id, String status, long userId) {
        requireOwner(id, userId);
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) throw invalid("状态只能是 ACTIVE 或 DISABLED");
        repository.updateStatus(id, status);
        return repository.findById(id).orElseThrow();
    }

    @Transactional
    public void delete(long id, long userId) {
        requireOwner(id, userId);
        if (repository.isReferenced(id)) repository.disable(id); else repository.delete(id);
    }

    /**
     * 校验题目存在且属于当前教师。
     *
     * <p>题目不存在返回 404、存在但不属于本人返回 403，两者区分开是为了让接口语义清晰；
     * 由于题库本身不是敏感资源，这里不刻意用 404 掩盖 403。
     */
    private QuestionView requireOwner(long id, long userId) {
        QuestionView question = repository.findById(id)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "QUESTION_NOT_FOUND", "题目不存在"));
        if (question.createdBy() != userId) {
            throw new DomainException(HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN", "不能访问其他教师创建的题目");
        }
        return question;
    }

    /**
     * 校验题型与标准答案、选项的一致性。
     *
     * <p>这一层不能省：前端表单会先校验一遍，但接口可能被直接调用，
     * 而一道「答案指向不存在选项」的题会让后续自动判分永远判 0 分。
     */
    private void validate(QuestionRequest request) {
        if (!repository.knowledgePointExists(request.knowledgePointId())) {
            throw invalid("知识点不存在");
        }
        List<OptionRequest> options = request.options() == null ? List.of() : request.options();
        Set<String> optionKeys = new HashSet<>();
        for (OptionRequest option : options) {
            String key = option.key().trim().toUpperCase(Locale.ROOT);
            if (!optionKeys.add(key)) throw invalid("选项键不能重复");
        }
        JsonNode answer = request.standardAnswer();
        switch (request.type()) {
            case SINGLE_CHOICE -> validateChoice(options, optionKeys, answer, true);
            case MULTIPLE_CHOICE -> validateChoice(options, optionKeys, answer, false);
            case TRUE_FALSE -> {
                if (!options.isEmpty() || !answer.isBoolean()) throw invalid("判断题不能包含选项，标准答案必须是布尔值");
            }
            // 简答题与编程题都是主观题：没有选项，标准答案只作为教师阅卷时的参考答案。
            case SHORT_ANSWER, PROGRAMMING -> {
                if (!options.isEmpty() || !answer.isTextual() || answer.asText().isBlank())
                    throw invalid("简答题和编程题不能包含选项，标准答案必须是非空参考答案文本");
            }
        }
    }

    /**
     * 校验选择题：至少两个选项，答案是非空选项键数组，且每个键都存在、不重复。
     *
     * @param single 为 true 时表示单选题，答案数组长度必须恰好为 1
     */
    private void validateChoice(List<OptionRequest> options, Set<String> optionKeys, JsonNode answer, boolean single) {
        if (options.size() < 2) throw invalid("选择题至少需要两个选项");
        if (!answer.isArray() || answer.isEmpty()) throw invalid("选择题标准答案必须是非空选项键数组");
        if (single && answer.size() != 1) throw invalid("单选题只能有一个标准答案");
        Set<String> answers = new HashSet<>();
        for (JsonNode node : answer) {
            if (!node.isTextual()) throw invalid("标准答案中的选项键必须是文本");
            String key = node.asText().trim().toUpperCase(Locale.ROOT);
            if (!optionKeys.contains(key)) throw invalid("标准答案必须引用已有选项");
            if (!answers.add(key)) throw invalid("标准答案不能包含重复选项");
        }
    }

    /** 构造题目校验失败异常，统一使用 400 与 {@code INVALID_QUESTION} 错误码。 */
    private DomainException invalid(String message) {
        return new DomainException(HttpStatus.BAD_REQUEST, "INVALID_QUESTION", message);
    }
}
