package com.smartexam.question;

import com.smartexam.common.ApiResponse;
import com.smartexam.common.PageResult;
import com.smartexam.question.QuestionModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/questions")
public class QuestionController {
    private final QuestionService service;

    public QuestionController(QuestionService service) { this.service = service; }

    @GetMapping
    public ApiResponse<PageResult<QuestionView>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) String keyword,
            @RequestParam(required=false) Type type,
            @RequestParam(required=false) Difficulty difficulty,
            @RequestParam(required=false) Long knowledgePointId,
            @RequestParam(defaultValue="1") @Min(1) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return ApiResponse.of(service.list(userId(jwt), keyword, type, difficulty, knowledgePointId, page, size));
    }

    @PostMapping
    public ApiResponse<QuestionView> create(@Valid @RequestBody QuestionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.create(request, userId(jwt)));
    }

    @GetMapping("/{id}")
    public ApiResponse<QuestionView> get(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.get(id, userId(jwt)));
    }

    @PutMapping("/{id}")
    public ApiResponse<QuestionView> update(@PathVariable long id, @Valid @RequestBody QuestionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.update(id, request, userId(jwt)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        service.delete(id, userId(jwt));
        return ResponseEntity.noContent().build();
    }

    private long userId(Jwt jwt) { return ((Number) jwt.getClaim("userId")).longValue(); }
}
