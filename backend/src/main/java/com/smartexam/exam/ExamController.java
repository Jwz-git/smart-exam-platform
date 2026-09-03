package com.smartexam.exam;

import com.smartexam.common.ApiResponse;
import com.smartexam.exam.ExamModels.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * 考试接口。教师和学生共用这一组路径，但能做的事不同：
 * 教师创建、发布、查看自己的考试；学生只能看到已发布的考试并开始作答。
 *
 * <p>因为同一前缀下角色不同，URL 级规则只能限定到「教师或学生」，
 * 更细的区分依靠方法上的 {@link PreAuthorize} 和 Service 里的归属校验。
 */
@RestController
@RequestMapping("/api/v1/exams")
public class ExamController {
    private final ExamService service;
    public ExamController(ExamService service) { this.service = service; }
    /** 按角色返回考试列表：教师看自己创建的全部考试，学生看所有已发布考试及本人答卷状态。 */
    @GetMapping public ApiResponse<List<ExamView>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(isTeacher(jwt) ? service.teacherExams(userId(jwt)) : service.studentExams(userId(jwt)));
    }
    /** 基于本人已发布的试卷创建考试草稿。 */
    @PostMapping @PreAuthorize("hasRole('TEACHER')")
    public ApiResponse<ExamView> create(@Valid @RequestBody ExamRequest request, @AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.createExam(request, userId(jwt))); }
    /** 发布考试，发布后学生才能在开放时间内看到并作答。 */
    @PostMapping("/{id}/publish") @PreAuthorize("hasRole('TEACHER')")
    public ApiResponse<ExamView> publish(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.publishExam(id, userId(jwt))); }
    /** 查看考试详情。教师限本人创建的考试，学生只能看到已发布的考试。 */
    @GetMapping("/{id}") public ApiResponse<ExamView> get(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.exam(id, userId(jwt), isTeacher(jwt))); }
    /**
     * 开始作答。幂等：已有未交卷的答卷时直接返回它（用于刷新或换设备后继续），
     * 已交卷则返回 409，不允许重考。
     */
    @PostMapping("/{id}/submissions") @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<SubmissionView> start(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.start(id, userId(jwt))); }
    /** 从令牌读取角色。令牌由服务端签名，客户端无法伪造该声明。 */
    private boolean isTeacher(Jwt jwt) { return "TEACHER".equals(jwt.getClaimAsString("role")); }

    /** 从令牌读取用户 ID，先转 Number 以兼容 Integer/Long 两种解析结果。 */
    private long userId(Jwt jwt) { return ((Number) jwt.getClaim("userId")).longValue(); }
}
