package com.smartexam.question;

import com.smartexam.common.DomainException;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgePointService {
    private final KnowledgePointRepository repository;

    public KnowledgePointService(KnowledgePointRepository repository) { this.repository = repository; }

    public List<KnowledgePointRepository.KnowledgePoint> list() { return repository.findAll(); }

    @Transactional
    public KnowledgePointRepository.KnowledgePoint create(String name, String description, long userId) {
        try {
            long id = repository.create(name.trim(), normalize(description), userId);
            return repository.findById(id).orElseThrow();
        } catch (DuplicateKeyException exception) {
            throw new DomainException(HttpStatus.CONFLICT, "KNOWLEDGE_POINT_EXISTS", "知识点名称已存在");
        }
    }

    @Transactional
    public KnowledgePointRepository.KnowledgePoint update(long id, String name, String description, long userId) {
        requireOwner(id, userId);
        try {
            repository.update(id, name.trim(), normalize(description));
            return repository.findById(id).orElseThrow();
        } catch (DuplicateKeyException exception) {
            throw new DomainException(HttpStatus.CONFLICT, "KNOWLEDGE_POINT_EXISTS", "知识点名称已存在");
        }
    }

    @Transactional
    public void delete(long id, long userId) {
        requireOwner(id, userId);
        if (repository.isUsed(id)) {
            throw new DomainException(HttpStatus.CONFLICT, "KNOWLEDGE_POINT_IN_USE", "知识点已被题目引用，不能删除");
        }
        repository.delete(id);
    }

    private void requireOwner(long id, long userId) {
        KnowledgePointRepository.KnowledgePoint point = repository.findById(id)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "KNOWLEDGE_POINT_NOT_FOUND", "知识点不存在"));
        if (point.createdBy() != userId) {
            throw new DomainException(HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN", "不能修改其他教师创建的知识点");
        }
    }

    private String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
