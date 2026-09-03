package com.smartexam.exam;

import com.smartexam.common.ApiResponse;
import com.smartexam.exam.ExamModels.*;
import com.smartexam.exam.GradingModels.SubmissionDetailView;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * 答卷接口：查看答卷详情、保存答案、交卷。
 *
 * <p>权限不能写在类上：读答卷详情教师和学生都要用（教师阅卷、学生回看），
 * 而保存答案和交卷只能是学生本人。因此这里逐方法标注 {@link PreAuthorize}，
 * 与 {@code SecurityConfig} 的 URL 规则形成双层防护——URL 规则放行「教师或学生」，
 * 方法注解再收紧到具体角色，Service 最后校验资源归属。
 */
@RestController
@RequestMapping("/api/v1/submissions")
public class SubmissionController {
    private final ExamService service;
    private final GradingService grading;

    public SubmissionController(ExamService service, GradingService grading) {
        this.service = service; this.grading = grading;
    }

    /**
     * 查看答卷详情，含逐题作答。
     *
     * <p>同一个响应结构服务三个场景：教师阅卷、学生答题后回看、学生查分。字段按
     * 「谁在看 + 成绩是否公布」裁剪，规则见 {@link GradingModels}——成绩公布前
     * 学生拿不到分数、标准答案、解析和评语。
     */
    @GetMapping("/{id}") @PreAuthorize("hasAnyRole('TEACHER','STUDENT')")
    public ApiResponse<SubmissionDetailView> get(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(grading.detail(id, userId(jwt), isTeacher(jwt)));
    }

    /**
     * 保存当前答案，可反复调用。前端在切题和每 30 秒时各调一次，
     * 保证超时自动交卷时服务端已经有学生的真实作答，而不是一份空白卷。
     */
    @PutMapping("/{id}/answers") @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<SubmissionView> save(@PathVariable long id,
            @Valid @RequestBody SaveAnswersRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.save(id, request, userId(jwt)));
    }

    /** 交卷并在同一事务内完成客观题判分。重复交卷返回 409，不会二次计分。 */
    @PostMapping("/{id}/submit") @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<SubmitResult> submit(@PathVariable long id,
            @AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.submit(id, userId(jwt))); }

    /** 从令牌读取角色。令牌由服务端签名，客户端无法伪造该声明。 */
    private boolean isTeacher(Jwt jwt) { return "TEACHER".equals(jwt.getClaimAsString("role")); }

    /** 从令牌读取当前用户 ID，用于校验答卷归属。 */
    private long userId(Jwt jwt) { return ((Number) jwt.getClaim("userId")).longValue(); }
}
