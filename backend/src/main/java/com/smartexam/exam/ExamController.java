package com.smartexam.exam;

import com.smartexam.common.ApiResponse;
import com.smartexam.exam.ExamModels.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/exams")
public class ExamController {
    private final ExamService service;
    public ExamController(ExamService service) { this.service = service; }
    @GetMapping public ApiResponse<List<ExamView>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(isTeacher(jwt) ? service.teacherExams(userId(jwt)) : service.studentExams(userId(jwt)));
    }
    @PostMapping @PreAuthorize("hasRole('TEACHER')")
    public ApiResponse<ExamView> create(@Valid @RequestBody ExamRequest request, @AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.createExam(request, userId(jwt))); }
    @PostMapping("/{id}/publish") @PreAuthorize("hasRole('TEACHER')")
    public ApiResponse<ExamView> publish(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.publishExam(id, userId(jwt))); }
    @GetMapping("/{id}") public ApiResponse<ExamView> get(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.exam(id, userId(jwt), isTeacher(jwt))); }
    @PostMapping("/{id}/submissions") @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<SubmissionView> start(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.start(id, userId(jwt))); }
    private boolean isTeacher(Jwt jwt) { return "TEACHER".equals(jwt.getClaimAsString("role")); }
    private long userId(Jwt jwt) { return ((Number) jwt.getClaim("userId")).longValue(); }
}
