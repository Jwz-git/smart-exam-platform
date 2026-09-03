package com.smartexam.exam;

import com.smartexam.common.ApiResponse;
import com.smartexam.exam.ExamModels.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/papers")
public class PaperController {
    private final ExamService service;
    public PaperController(ExamService service) { this.service = service; }
    @GetMapping public ApiResponse<List<PaperView>> list(@AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.papers(userId(jwt))); }
    @PostMapping public ApiResponse<PaperView> create(@Valid @RequestBody PaperRequest request, @AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.createPaper(request, userId(jwt))); }
    @GetMapping("/{id}") public ApiResponse<PaperView> get(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.paper(id, userId(jwt))); }
    @PostMapping("/{id}/publish") public ApiResponse<PaperView> publish(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.publishPaper(id, userId(jwt))); }
    private long userId(Jwt jwt) { return ((Number) jwt.getClaim("userId")).longValue(); }
}
