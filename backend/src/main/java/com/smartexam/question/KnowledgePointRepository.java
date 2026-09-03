package com.smartexam.question;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 知识点表读写。 */
@Repository
public class KnowledgePointRepository {
    private final JdbcClient jdbc;

    public KnowledgePointRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** 按名称升序返回全部知识点，保证下拉框顺序稳定。 */
    public List<KnowledgePoint> findAll() {
        return jdbc.sql("SELECT id, name, description, created_by FROM knowledge_point ORDER BY name")
                .query(KnowledgePoint.class).list();
    }

    public Optional<KnowledgePoint> findById(long id) {
        return jdbc.sql("SELECT id, name, description, created_by FROM knowledge_point WHERE id=:id")
                .param("id", id).query(KnowledgePoint.class).optional();
    }

    /** 插入知识点并返回自增主键。 */
    public long create(String name, String description, long creatorId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO knowledge_point(name, description, created_by) VALUES (:name,:description,:creator)")
                .param("name", name).param("description", description).param("creator", creatorId)
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    public int update(long id, String name, String description) {
        return jdbc.sql("UPDATE knowledge_point SET name=:name, description=:description WHERE id=:id")
                .param("name", name).param("description", description).param("id", id).update();
    }

    public int delete(long id) {
        return jdbc.sql("DELETE FROM knowledge_point WHERE id=:id").param("id", id).update();
    }

    /** 判断是否已有题目引用该知识点，用于阻止删除。 */
    public boolean isUsed(long id) {
        return jdbc.sql("SELECT COUNT(*) FROM question WHERE knowledge_point_id=:id")
                .param("id", id).query(Long.class).single() > 0;
    }

    /** 知识点。{@code createdBy} 用于归属校验，仅创建者可改删。 */
    public record KnowledgePoint(long id, String name, String description, long createdBy) {}
}
