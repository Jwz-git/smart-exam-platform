package com.smartexam.question;

import com.smartexam.common.DomainException;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识点业务规则。
 *
 * <p>知识点全局可见（组卷时需要按知识点筛选整个题库），但只有创建者能修改和删除；
 * 已被题目引用的知识点不允许删除，否则题目会失去分类依据。
 */
@Service
public class KnowledgePointService {
    private final KnowledgePointRepository repository;

    public KnowledgePointService(KnowledgePointRepository repository) { this.repository = repository; }

    /** 返回全部知识点，按名称排序，供筛选下拉框使用。 */
    public List<KnowledgePointRepository.KnowledgePoint> list() { return repository.findAll(); }

    /** 新增知识点。名称在数据库上有唯一约束，重复时转成 409 而不是把数据库异常抛给调用方。 */
    @Transactional
    public KnowledgePointRepository.KnowledgePoint create(String name, String description, long userId) {
        try {
            long id = repository.create(name.trim(), normalize(description), userId);
            return repository.findById(id).orElseThrow();
        } catch (DuplicateKeyException exception) {
            throw new DomainException(HttpStatus.CONFLICT, "KNOWLEDGE_POINT_EXISTS", "知识点名称已存在");
        }
    }

    /** 编辑知识点，仅创建者可操作。 */
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

    /** 删除知识点。被题目引用时返回 409，提示先调整题目的知识点归属。 */
    @Transactional
    public void delete(long id, long userId) {
        requireOwner(id, userId);
        if (repository.isUsed(id)) {
            throw new DomainException(HttpStatus.CONFLICT, "KNOWLEDGE_POINT_IN_USE", "知识点已被题目引用，不能删除");
        }
        repository.delete(id);
    }

    /** 校验知识点存在且属于当前教师。 */
    private void requireOwner(long id, long userId) {
        KnowledgePointRepository.KnowledgePoint point = repository.findById(id)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "KNOWLEDGE_POINT_NOT_FOUND", "知识点不存在"));
        if (point.createdBy() != userId) {
            throw new DomainException(HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN", "不能修改其他教师创建的知识点");
        }
    }

    /** 把空字符串和纯空白统一成 null，避免数据库里出现“看起来有值其实是空格”的描述。 */
    private String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
