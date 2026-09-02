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

@Service
public class QuestionService {
    private final QuestionRepository repository;

    public QuestionService(QuestionRepository repository) { this.repository = repository; }

    public PageResult<QuestionView> list(long userId, String keyword, Type type, Difficulty difficulty,
            Long knowledgePointId, int page, int size) {
        return repository.findAll(userId, keyword, type, difficulty, knowledgePointId, page, size);
    }

    public QuestionView get(long id, long userId) { return requireOwner(id, userId); }

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

    @Transactional
    public void delete(long id, long userId) {
        requireOwner(id, userId);
        if (repository.isReferenced(id)) repository.disable(id); else repository.delete(id);
    }

    private QuestionView requireOwner(long id, long userId) {
        QuestionView question = repository.findById(id)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "QUESTION_NOT_FOUND", "题目不存在"));
        if (question.createdBy() != userId) {
            throw new DomainException(HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN", "不能访问其他教师创建的题目");
        }
        return question;
    }

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
            case SHORT_ANSWER -> {
                if (!options.isEmpty() || !answer.isTextual() || answer.asText().isBlank())
                    throw invalid("简答题不能包含选项，标准答案必须是非空文本");
            }
        }
    }

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

    private DomainException invalid(String message) {
        return new DomainException(HttpStatus.BAD_REQUEST, "INVALID_QUESTION", message);
    }
}
