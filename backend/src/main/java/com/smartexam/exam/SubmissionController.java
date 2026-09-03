package com.smartexam.exam;

import com.smartexam.common.ApiResponse;
import com.smartexam.exam.ExamModels.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/submissions")
@PreAuthorize("hasRole('STUDENT')")
public class SubmissionController {
    private final ExamService service;
    public SubmissionController(ExamService service) { this.service = service; }
    @PutMapping("/{id}/answers") public ApiResponse<SubmissionView> save(@PathVariable long id,
            @Valid @RequestBody SaveAnswersRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.save(id, request, userId(jwt)));
    }
    @PostMapping("/{id}/submit") public ApiResponse<SubmitResult> submit(@PathVariable long id,
            @AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.submit(id, userId(jwt))); }
    private long userId(Jwt jwt) { return ((Number) jwt.getClaim("userId")).longValue(); }
}
