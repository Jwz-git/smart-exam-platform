package com.smartexam.question;

import com.smartexam.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 知识点管理接口，仅教师可访问。知识点是题目的分类依据，也是组卷时的筛选维度。
 */
@RestController
@RequestMapping("/api/v1/knowledge-points")
public class KnowledgePointController {
    private final KnowledgePointService service;

    public KnowledgePointController(KnowledgePointService service) { this.service = service; }

    /** 查询全部知识点。这里不按创建者过滤：组卷需要看到题库里所有可用分类。 */
    @GetMapping
    public ApiResponse<List<KnowledgePointRepository.KnowledgePoint>> list() { return ApiResponse.of(service.list()); }

    /** 新增知识点，名称全局唯一。 */
    @PostMapping
    public ApiResponse<KnowledgePointRepository.KnowledgePoint> create(@Valid @RequestBody Request request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.create(request.name(), request.description(), jwt.getClaim("userId")));
    }

    /** 编辑知识点，仅创建者可操作，越权返回 403。 */
    @PutMapping("/{id}")
    public ApiResponse<KnowledgePointRepository.KnowledgePoint> update(@PathVariable long id,
            @Valid @RequestBody Request request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.update(id, request.name(), request.description(), jwt.getClaim("userId")));
    }

    /** 删除知识点，被题目引用时返回 409。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        service.delete(id, jwt.getClaim("userId"));
        return ResponseEntity.noContent().build();
    }

    /** 知识点请求体，描述可选。 */
    public record Request(@NotBlank @Size(max=100) String name, @Size(max=500) String description) {}
}
