package com.smartexam.stats;

import com.smartexam.common.ApiResponse;
import com.smartexam.stats.StatsModels.ExamAnalysisView;
import com.smartexam.stats.StatsModels.OverviewView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计分析接口，只有教师可用。
 *
 * <p>两个端点的边界是「全局」与「单场」：概况回答「我的题库和考试整体是什么状况」，
 * 考试分析回答「这一场考得怎么样、哪道题最难」。都是只读 GET，不改变任何业务状态。
 *
 * <p>路径里没有教师 ID：统计范围一律取令牌里的当前用户，不接受客户端指定，
 * 否则改一个查询参数就能看到别人的题库规模。
 */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {
    private final StatsService service;
    public StatsController(StatsService service) { this.service = service; }

    /** 题库分布与教学活动概况，范围为当前教师自己的数据。 */
    @GetMapping("/overview") @PreAuthorize("hasRole('TEACHER')")
    public ApiResponse<OverviewView> overview(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.overview(userId(jwt)));
    }

    /** 单场考试的成绩分布与逐题正确率。考试不存在返回 404，不是自己的考试返回 403。 */
    @GetMapping("/exams/{id}") @PreAuthorize("hasRole('TEACHER')")
    public ApiResponse<ExamAnalysisView> examAnalysis(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.examAnalysis(id, userId(jwt)));
    }

    /** 从令牌读取用户 ID，先转 Number 以兼容 Integer/Long 两种解析结果。 */
    private long userId(Jwt jwt) { return ((Number) jwt.getClaim("userId")).longValue(); }
}
