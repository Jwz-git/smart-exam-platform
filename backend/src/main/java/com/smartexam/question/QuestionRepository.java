package com.smartexam.question;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.common.PageResult;
import com.smartexam.question.QuestionModels.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 题库表读写。题目与选项分表存储，标准答案以 JSON 形式保存在 {@code question.standard_answer}。
 *
 * <p>筛选条件是动态拼接的：只把用户实际用到的条件加进 WHERE，避免写成
 * {@code (:type IS NULL OR type = :type)} 那样让索引失效的形式。拼接只涉及固定的列名和
 * 占位符名，用户输入始终通过具名参数传递，不存在注入风险。
 */
@Repository
public class QuestionRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public QuestionRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 分页查询题目。
     *
     * <p>先用 COUNT 取总数再取当页数据，是两条独立查询：分页组件需要总页数，
     * 而 MySQL 的 {@code LIMIT ... OFFSET} 本身不返回总数。
     */
    public PageResult<QuestionView> findAll(long creatorId, String keyword, Type type, Difficulty difficulty,
            Long knowledgePointId, String status, int page, int size) {
        // 题库列表同时返回启用和停用题目，由界面显示状态并提供启用/停用操作；status 非空时再按状态过滤。
        StringBuilder where = new StringBuilder(" WHERE q.created_by=:creator");
        Map<String, Object> params = new HashMap<>();
        params.put("creator", creatorId);
        if (status != null && !status.isBlank()) {
            where.append(" AND q.status=:status");
            params.put("status", status);
        }
        if (keyword != null && !keyword.isBlank()) {
            // 关键词同时匹配题干和标签，便于按“Java, 特点”这类标签检索。
            where.append(" AND (q.stem LIKE :keyword ESCAPE '!' OR q.tags LIKE :keyword ESCAPE '!')");
            params.put("keyword", "%" + escapeLike(keyword.trim()) + "%");
        }
        if (type != null) { where.append(" AND q.type=:type"); params.put("type", type.name()); }
        if (difficulty != null) { where.append(" AND q.difficulty=:difficulty"); params.put("difficulty", difficulty.name()); }
        if (knowledgePointId != null) { where.append(" AND q.knowledge_point_id=:point"); params.put("point", knowledgePointId); }

        long total = statement("SELECT COUNT(*) FROM question q" + where, params).query(Long.class).single();
        String select = """
                SELECT q.id,q.type,q.stem,q.difficulty,q.tags,q.standard_answer,q.explanation,q.suggested_score,
                       q.knowledge_point_id,k.name AS knowledge_point_name,q.created_by,q.status
                FROM question q JOIN knowledge_point k ON k.id=q.knowledge_point_id
                """ + where + " ORDER BY q.id DESC LIMIT :limit OFFSET :offset";
        Map<String, Object> pageParams = new HashMap<>(params);
        pageParams.put("limit", size);
        pageParams.put("offset", (page - 1) * size);
        List<QuestionView> items = statement(select, pageParams).query(this::mapQuestion).list();
        return new PageResult<>(items, page, size, total);
    }

    /** 按 ID 查询题目，含选项；不做归属校验，由 Service 负责。 */
    public Optional<QuestionView> findById(long id) {
        String sql = """
                SELECT q.id,q.type,q.stem,q.difficulty,q.tags,q.standard_answer,q.explanation,q.suggested_score,
                       q.knowledge_point_id,k.name AS knowledge_point_name,q.created_by,q.status
                FROM question q JOIN knowledge_point k ON k.id=q.knowledge_point_id WHERE q.id=:id
                """;
        return jdbc.sql(sql).param("id", id).query(this::mapQuestion).optional();
    }

    /** 插入题目及其选项，返回题目主键。调用方需保证处于事务中。 */
    public long create(QuestionRequest request, long creatorId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO question(type,stem,difficulty,tags,standard_answer,explanation,suggested_score,
                                     knowledge_point_id,created_by,status)
                VALUES (:type,:stem,:difficulty,:tags,:answer,:explanation,:score,:point,:creator,'ACTIVE')
                """)
                .param("type", request.type().name()).param("stem", request.stem().trim())
                .param("difficulty", request.difficulty().name()).param("tags", normalizeTags(request.tags()))
                .param("answer", request.standardAnswer().toString())
                .param("explanation", normalize(request.explanation())).param("score", request.suggestedScore())
                .param("point", request.knowledgePointId()).param("creator", creatorId).update(keys, "id");
        long id = keys.getKey().longValue();
        replaceOptions(id, request.options());
        return id;
    }

    /** 更新题目，并用先删后插的方式整体替换选项。 */
    public void update(long id, QuestionRequest request) {
        jdbc.sql("""
                UPDATE question SET type=:type,stem=:stem,difficulty=:difficulty,tags=:tags,standard_answer=:answer,
                    explanation=:explanation,suggested_score=:score,knowledge_point_id=:point WHERE id=:id
                """)
                .param("type", request.type().name()).param("stem", request.stem().trim())
                .param("difficulty", request.difficulty().name()).param("tags", normalizeTags(request.tags()))
                .param("answer", request.standardAnswer().toString())
                .param("explanation", normalize(request.explanation())).param("score", request.suggestedScore())
                .param("point", request.knowledgePointId()).param("id", id).update();
        jdbc.sql("DELETE FROM question_option WHERE question_id=:id").param("id", id).update();
        replaceOptions(id, request.options());
    }

    /** 判断题目是否已被任何试卷引用，用于决定删除还是停用。 */
    public boolean isReferenced(long id) {
        return jdbc.sql("SELECT COUNT(*) FROM paper_question WHERE question_id=:id")
                .param("id", id).query(Long.class).single() > 0;
    }

    /** 物理删除题目及其选项。仅在题目未被任何试卷引用时调用。 */
    public void delete(long id) {
        jdbc.sql("DELETE FROM question_option WHERE question_id=:id").param("id", id).update();
        jdbc.sql("DELETE FROM question WHERE id=:id").param("id", id).update();
    }

    /** 停用题目，保留数据。 */
    public void disable(long id) { updateStatus(id, "DISABLED"); }

    /** 直接设置题目状态，取值由 Service 限定为 ACTIVE 或 DISABLED。 */
    public void updateStatus(long id, String status) {
        jdbc.sql("UPDATE question SET status=:status WHERE id=:id").param("status", status).param("id", id).update();
    }

    /** 校验知识点是否存在，避免题目挂到一个不存在的分类上。 */
    public boolean knowledgePointExists(long id) {
        return jdbc.sql("SELECT COUNT(*) FROM knowledge_point WHERE id=:id").param("id", id)
                .query(Long.class).single() > 0;
    }

    /**
     * 判断该教师题库里是否已有完全相同题干的题目，供批量导入判重。
     *
     * <p>范围限定在 {@code created_by}，与题库列表的可见范围一致：另一位教师有同名题目
     * 不该阻止本人导入，而且那道题在这里既看不到也改不了，报「已存在」只会让人莫名其妙。
     *
     * <p>刻意不在数据库上加唯一约束：同一题干配不同选项是合法的出题手法，
     * 判重是导入这一个入口的便利功能，不是题库的完整性规则。
     */
    public boolean existsByStem(long creatorId, String stem) {
        return jdbc.sql("SELECT COUNT(*) FROM question WHERE created_by=:creator AND stem=:stem")
                .param("creator", creatorId).param("stem", stem).query(Long.class).single() > 0;
    }

    /**
     * 按规则取候选题目 ID，供规则自动组卷抽题。
     *
     * <p>只返回 ID 而不是完整题目：抽中的是少数，先取一串 ID 在内存里洗牌、切片，
     * 再对抽中的那几道逐一取详情，比把整个候选池连选项一起查出来便宜得多。
     *
     * <p>固定只取 {@code ACTIVE}：停用题目本来就不允许加入新试卷，不该出现在候选池里，
     * 否则抽中之后才被组卷校验拒掉。范围同样限定 {@code created_by}，与题库列表一致。
     *
     * <p>刻意不用 {@code ORDER BY RAND()}：随机在 Java 里做。一是 {@code RAND()} 的行为
     * 与数据库方言绑定（自动化测试跑 H2、生产跑 MySQL），二是「按 ID 取回再洗牌」可以
     * 顺便把「候选池有多少道」这个数字告诉教师，而 SQL 层随机取 N 条就拿不到池子大小了。
     */
    public List<Long> findComposeCandidateIds(long creatorId, Type type, Difficulty difficulty,
            Long knowledgePointId) {
        StringBuilder sql = new StringBuilder(
                "SELECT id FROM question WHERE created_by=:creator AND status='ACTIVE'");
        Map<String, Object> params = new HashMap<>();
        params.put("creator", creatorId);
        if (type != null) { sql.append(" AND type=:type"); params.put("type", type.name()); }
        if (difficulty != null) { sql.append(" AND difficulty=:difficulty"); params.put("difficulty", difficulty.name()); }
        if (knowledgePointId != null) { sql.append(" AND knowledge_point_id=:point"); params.put("point", knowledgePointId); }
        sql.append(" ORDER BY id");
        return statement(sql.toString(), params).query(Long.class).list();
    }

    /** 按顺序写入选项，{@code display_order} 从 1 开始，选项键统一转大写以便和答案比对。 */
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

    /**
     * 把结果集一行映射成题目视图，并补齐选项。
     *
     * <p>已知不足：这里对每一行再查一次选项表，一页 20 条会产生 21 次查询（N+1）。
     * 当前题库规模下可以接受，若列表变慢应改为一次性按 {@code question_id IN (...)} 取回
     * 所有选项再在内存里分组。
     */
    private QuestionView mapQuestion(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        long id = rs.getLong("id");
        List<OptionView> options = jdbc.sql("""
                SELECT id,option_key,content,display_order FROM question_option
                WHERE question_id=:id ORDER BY display_order
                """).param("id", id).query((optionRs, optionRow) -> new OptionView(optionRs.getLong("id"),
                        optionRs.getString("option_key"), optionRs.getString("content"),
                        optionRs.getInt("display_order"))).list();
        return new QuestionView(id, Type.valueOf(rs.getString("type")), rs.getString("stem"),
                Difficulty.valueOf(rs.getString("difficulty")), rs.getString("tags"),
                readJson(rs.getString("standard_answer")),
                rs.getString("explanation"), rs.getBigDecimal("suggested_score"),
                rs.getLong("knowledge_point_id"), rs.getString("knowledge_point_name"),
                rs.getLong("created_by"), rs.getString("status"), options);
    }

    /** 解析数据库中的 JSON 字段。这里的数据是本系统自己写入的，解析失败说明数据已被破坏，直接抛错。 */
    private JsonNode readJson(String value) {
        try { return objectMapper.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("数据库中的标准答案不是合法 JSON", exception); }
    }

    /** 把动态收集的参数逐个绑定到语句上。 */
    private JdbcClient.StatementSpec statement(String sql, Map<String, Object> params) {
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        for (Map.Entry<String, Object> entry : params.entrySet()) statement = statement.param(entry.getKey(), entry.getValue());
        return statement;
    }

    private String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    /** 把标签整理成“a, b”形式，去掉空项和多余空格，保持列表展示一致。 */
    private String normalizeTags(String value) {
        if (value == null || value.isBlank()) return null;
        String joined = Arrays.stream(value.split("[,，]"))
                .map(String::trim).filter(item -> !item.isEmpty())
                .collect(Collectors.joining(", "));
        return joined.isEmpty() ? null : joined;
    }

    /** 转义 LIKE 通配符，避免用户输入的 % 和 _ 变成模糊匹配；escape 字符统一用 '!'。 */
    private String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
