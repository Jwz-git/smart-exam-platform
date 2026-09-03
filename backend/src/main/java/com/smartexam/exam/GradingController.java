package com.smartexam.exam;

import com.smartexam.common.ApiResponse;
import com.smartexam.exam.GradingModels.ExamResultsView;
import com.smartexam.exam.GradingModels.GradingBoardView;
import com.smartexam.exam.GradingModels.MyResultItemView;
import com.smartexam.exam.GradingModels.ScoreRequest;
import com.smartexam.exam.GradingModels.SubmissionDetailView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * 阅卷与成绩接口。
 *
 * <p>路径按「谁的资源」分成两组，而不是按功能堆在一个前缀下：
 * <ul>
 *   <li>{@code /exams/{id}/grading}、{@code /exams/{id}/results}、{@code /exams/{id}/publish-results}
 *       是教师对某场考试的操作；</li>
 *   <li>{@code /my/results} 是学生对本人成绩的查询，路径里没有学生 ID——
 *       身份一律从令牌取，不接受客户端传入，避免把「查谁的成绩」交给请求参数决定。</li>
 * </ul>
 *
 * <p>每个方法都标注了 {@link PreAuthorize}，与 {@code SecurityConfig} 的 URL 规则形成双层防护；
 * 资源归属（这场考试是不是我建的、这份答卷是不是我的）由 {@link GradingService} 再校验一次。
 */
@RestController
@RequestMapping("/api/v1")
public class GradingController {
    private final GradingService service;
    public GradingController(GradingService service) { this.service = service; }

    /** 阅卷面板：本场考试的答卷列表、每份的主观题数量与未评分数量。 */
    @GetMapping("/exams/{id}/grading") @PreAuthorize("hasRole('TEACHER')")
    public ApiResponse<GradingBoardView> board(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.board(id, userId(jwt)));
    }

    /**
     * 给一道主观题评分并写评语，返回重算后的整份答卷。
     *
     * <p>用 PUT 而不是 POST：同一道题反复评分的结果只取决于最后一次请求，是幂等的覆盖语义。
     */
    @PutMapping("/submission-answers/{id}/score") @PreAuthorize("hasRole('TEACHER')")
    public ApiResponse<SubmissionDetailView> score(@PathVariable long id, @Valid @RequestBody ScoreRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.score(id, request, userId(jwt)));
    }

    /** 公布成绩。要求无人在作答且主观题已全部批完；公布后评分冻结、考试关闭。 */
    @PostMapping("/exams/{id}/publish-results") @PreAuthorize("hasRole('TEACHER')")
    public ApiResponse<ExamResultsView> publishResults(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.publishResults(id, userId(jwt)));
    }

    /** 教师查看完整排名与统计。公布前也可查看，便于先核对分布再决定是否公布。 */
    @GetMapping("/exams/{id}/results") @PreAuthorize("hasRole('TEACHER')")
    public ApiResponse<ExamResultsView> results(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.results(id, userId(jwt)));
    }

    /** 学生查看本人已公布的成绩列表，含本人名次和参与人数，不含其他学生信息。 */
    @GetMapping("/my/results") @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<List<MyResultItemView>> myResults(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.myResults(userId(jwt)));
    }

    /**
     * 学生查看本人某份答卷的完整详情。
     *
     * <p>与 {@code GET /submissions/{id}} 返回同一个结构，保留这条 {@code /my} 路径是为了
     * 让前端的「我的成绩」列表和详情共用一组语义明确的路径；归属与公布状态的裁剪逻辑完全一致。
     */
    @GetMapping("/my/results/{submissionId}") @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<SubmissionDetailView> myResult(@PathVariable long submissionId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.detail(submissionId, userId(jwt), false));
    }

    /** 从令牌读取用户 ID，先转 Number 以兼容 Integer/Long 两种解析结果。 */
    private long userId(Jwt jwt) { return ((Number) jwt.getClaim("userId")).longValue(); }
}
