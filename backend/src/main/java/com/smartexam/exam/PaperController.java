package com.smartexam.exam;

import com.smartexam.common.ApiResponse;
import com.smartexam.exam.ExamModels.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * 试卷接口，仅教师可访问。
 *
 * <p>试卷只提供「创建草稿 → 预览 → 发布」三步，没有编辑和删除接口：
 * 已发布试卷可能已被考试和答卷引用，直接改动会破坏历史成绩的可追溯性。
 * 需要调整时应重新组一份新试卷。
 */
@RestController
@RequestMapping("/api/v1/papers")
public class PaperController {
    private final ExamService service;
    public PaperController(ExamService service) { this.service = service; }
    /** 查询本人创建的试卷。 */
    @GetMapping public ApiResponse<List<PaperView>> list(@AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.papers(userId(jwt))); }
    /** 创建试卷草稿并选题，同时校验分值合计等于总分。 */
    @PostMapping public ApiResponse<PaperView> create(@Valid @RequestBody PaperRequest request, @AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.createPaper(request, userId(jwt))); }
    /** 预览试卷。返回的题目为快照内容，且不含标准答案。 */
    @GetMapping("/{id}") public ApiResponse<PaperView> get(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.paper(id, userId(jwt))); }
    /** 发布试卷。仅草稿可发布，发布后才能用于创建考试。 */
    @PostMapping("/{id}/publish") public ApiResponse<PaperView> publish(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.publishPaper(id, userId(jwt))); }
    /** 从令牌读取当前教师 ID。 */
    private long userId(Jwt jwt) { return ((Number) jwt.getClaim("userId")).longValue(); }
}
