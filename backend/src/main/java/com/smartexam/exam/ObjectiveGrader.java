package com.smartexam.exam;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 客观题判分规则：判断一份作答是否与标准答案一致。
 *
 * <p>从 {@code ExamService} 里抽出来，是因为现在有两处需要判客观题：<b>交卷</b>与<b>错题重练</b>。
 * 两处必须用同一套规则——如果重练自己写一遍比较逻辑，出现「考试判错、重练判对」时，
 * 学生完全无法理解到底哪个是对的，而这类分歧只会在考完之后才暴露。
 * 抽出共用组件的理由与 {@code AnswerNormalizer}（AI 出题与批量导入共用答案归一化）完全一样。
 *
 * <p>规则本身没有变：
 * <ul>
 *   <li>单选、判断：答对得满分，否则 0 分；</li>
 *   <li>多选：答案集合必须完全一致才得分，少选和多选都不给部分分；</li>
 *   <li>未作答（{@code null} 或 JSON null）一律不得分；</li>
 *   <li>选项键忽略顺序与大小写差异，{@code ["a","B"]} 与 {@code ["B","A"]} 等价。</li>
 * </ul>
 */
@Component
public class ObjectiveGrader {
    /**
     * 是否为客观题（可自动判分）。
     *
     * <p>取反于 {@link GradingModels#SUBJECTIVE_TYPES}，因此新增主观题题型时只改那一处，
     * 不会出现「判分当客观题、阅卷当主观题」的分裂。
     */
    public boolean isObjective(String type) { return !GradingModels.SUBJECTIVE_TYPES.contains(type); }

    /**
     * 比对标准答案与学生作答。
     *
     * <p>选择题按集合比较，忽略选项顺序和大小写差异；多选必须完全一致，少选或多选均不得分。
     * 判断题直接比较布尔值。未作答一律判错。
     */
    public boolean matches(JsonNode expected, JsonNode actual) {
        if (expected == null || actual == null || actual.isNull()) return false;
        if (expected.isArray()) return keys(expected).equals(keys(actual));
        return expected.equals(actual);
    }

    /** 把选项键数组转成规范化集合：去空格、转大写，使 {@code ["a","B"]} 与 {@code ["A","b"]} 等价。 */
    private Set<String> keys(JsonNode node) {
        Set<String> values = new HashSet<>();
        if (!node.isArray()) return values;
        node.forEach(value -> values.add(value.asText().trim().toUpperCase(Locale.ROOT)));
        return values;
    }
}
