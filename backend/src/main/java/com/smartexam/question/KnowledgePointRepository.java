package com.smartexam.question;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgePointRepository {
    private final JdbcClient jdbc;

    public KnowledgePointRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<KnowledgePoint> findAll() {
        return jdbc.sql("SELECT id, name, description, created_by FROM knowledge_point ORDER BY name")
                .query(KnowledgePoint.class).list();
    }

    public Optional<KnowledgePoint> findById(long id) {
        return jdbc.sql("SELECT id, name, description, created_by FROM knowledge_point WHERE id=:id")
                .param("id", id).query(KnowledgePoint.class).optional();
    }

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

    public boolean isUsed(long id) {
        return jdbc.sql("SELECT COUNT(*) FROM question WHERE knowledge_point_id=:id")
                .param("id", id).query(Long.class).single() > 0;
    }

    public record KnowledgePoint(long id, String name, String description, long createdBy) {}
}
