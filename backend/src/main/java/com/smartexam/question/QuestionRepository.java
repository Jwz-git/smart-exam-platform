package com.smartexam.question;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.common.PageResult;
import com.smartexam.question.QuestionModels.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class QuestionRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public QuestionRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public PageResult<QuestionView> findAll(long creatorId, String keyword, Type type, Difficulty difficulty,
            Long knowledgePointId, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE q.created_by=:creator AND q.status='ACTIVE'");
        Map<String, Object> params = new HashMap<>();
        params.put("creator", creatorId);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND q.stem LIKE :keyword");
            params.put("keyword", "%" + keyword.trim() + "%");
        }
        if (type != null) { where.append(" AND q.type=:type"); params.put("type", type.name()); }
        if (difficulty != null) { where.append(" AND q.difficulty=:difficulty"); params.put("difficulty", difficulty.name()); }
        if (knowledgePointId != null) { where.append(" AND q.knowledge_point_id=:point"); params.put("point", knowledgePointId); }

        long total = statement("SELECT COUNT(*) FROM question q" + where, params).query(Long.class).single();
        String select = """
                SELECT q.id,q.type,q.stem,q.difficulty,q.standard_answer,q.explanation,q.suggested_score,
                       q.knowledge_point_id,k.name AS knowledge_point_name,q.created_by,q.status
                FROM question q JOIN knowledge_point k ON k.id=q.knowledge_point_id
                """ + where + " ORDER BY q.id DESC LIMIT :limit OFFSET :offset";
        Map<String, Object> pageParams = new HashMap<>(params);
        pageParams.put("limit", size);
        pageParams.put("offset", (page - 1) * size);
        List<QuestionView> items = statement(select, pageParams).query(this::mapQuestion).list();
        return new PageResult<>(items, page, size, total);
    }

    public Optional<QuestionView> findById(long id) {
        String sql = """
                SELECT q.id,q.type,q.stem,q.difficulty,q.standard_answer,q.explanation,q.suggested_score,
                       q.knowledge_point_id,k.name AS knowledge_point_name,q.created_by,q.status
                FROM question q JOIN knowledge_point k ON k.id=q.knowledge_point_id WHERE q.id=:id
                """;
        return jdbc.sql(sql).param("id", id).query(this::mapQuestion).optional();
    }

    public long create(QuestionRequest request, long creatorId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO question(type,stem,difficulty,standard_answer,explanation,suggested_score,
                                     knowledge_point_id,created_by,status)
                VALUES (:type,:stem,:difficulty,:answer,:explanation,:score,:point,:creator,'ACTIVE')
                """)
                .param("type", request.type().name()).param("stem", request.stem().trim())
                .param("difficulty", request.difficulty().name()).param("answer", request.standardAnswer().toString())
                .param("explanation", normalize(request.explanation())).param("score", request.suggestedScore())
                .param("point", request.knowledgePointId()).param("creator", creatorId).update(keys, "id");
        long id = keys.getKey().longValue();
        replaceOptions(id, request.options());
        return id;
    }

    public void update(long id, QuestionRequest request) {
        jdbc.sql("""
                UPDATE question SET type=:type,stem=:stem,difficulty=:difficulty,standard_answer=:answer,
                    explanation=:explanation,suggested_score=:score,knowledge_point_id=:point WHERE id=:id
                """)
                .param("type", request.type().name()).param("stem", request.stem().trim())
                .param("difficulty", request.difficulty().name()).param("answer", request.standardAnswer().toString())
                .param("explanation", normalize(request.explanation())).param("score", request.suggestedScore())
                .param("point", request.knowledgePointId()).param("id", id).update();
        jdbc.sql("DELETE FROM question_option WHERE question_id=:id").param("id", id).update();
        replaceOptions(id, request.options());
    }

    public boolean isReferenced(long id) {
        return jdbc.sql("SELECT COUNT(*) FROM paper_question WHERE question_id=:id")
                .param("id", id).query(Long.class).single() > 0;
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM question_option WHERE question_id=:id").param("id", id).update();
        jdbc.sql("DELETE FROM question WHERE id=:id").param("id", id).update();
    }

    public void disable(long id) {
        jdbc.sql("UPDATE question SET status='DISABLED' WHERE id=:id").param("id", id).update();
    }

    public boolean knowledgePointExists(long id) {
        return jdbc.sql("SELECT COUNT(*) FROM knowledge_point WHERE id=:id").param("id", id)
                .query(Long.class).single() > 0;
    }

    private void replaceOptions(long questionId, List<OptionRequest> options) {
        if (options == null) return;
        for (int index = 0; index < options.size(); index++) {
            OptionRequest option = options.get(index);
            jdbc.sql("""
                    INSERT INTO question_option(question_id,option_key,content,display_order)
                    VALUES (:question,:key,:content,:position)
                    """).param("question", questionId).param("key", option.key().trim().toUpperCase(Locale.ROOT))
                    .param("content", option.content().trim()).param("position", index + 1).update();
        }
    }

    private QuestionView mapQuestion(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        long id = rs.getLong("id");
        List<OptionView> options = jdbc.sql("""
                SELECT id,option_key,content,display_order FROM question_option
                WHERE question_id=:id ORDER BY display_order
                """).param("id", id).query((optionRs, optionRow) -> new OptionView(optionRs.getLong("id"),
                        optionRs.getString("option_key"), optionRs.getString("content"),
                        optionRs.getInt("display_order"))).list();
        return new QuestionView(id, Type.valueOf(rs.getString("type")), rs.getString("stem"),
                Difficulty.valueOf(rs.getString("difficulty")), readJson(rs.getString("standard_answer")),
                rs.getString("explanation"), rs.getBigDecimal("suggested_score"),
                rs.getLong("knowledge_point_id"), rs.getString("knowledge_point_name"),
                rs.getLong("created_by"), rs.getString("status"), options);
    }

    private JsonNode readJson(String value) {
        try { return objectMapper.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("数据库中的标准答案不是合法 JSON", exception); }
    }

    private JdbcClient.StatementSpec statement(String sql, Map<String, Object> params) {
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        for (Map.Entry<String, Object> entry : params.entrySet()) statement = statement.param(entry.getKey(), entry.getValue());
        return statement;
    }

    private String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
