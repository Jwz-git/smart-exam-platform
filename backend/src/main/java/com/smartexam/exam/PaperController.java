package com.smartexam.exam;

import com.smartexam.common.ApiResponse;
import com.smartexam.exam.AutoComposeModels.AutoComposePlan;
import com.smartexam.exam.AutoComposeModels.AutoComposeRequest;
import com.smartexam.exam.ExamModels.*;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * 试卷接口，仅教师可访问。
 *
 * <p>试卷只提供「创建草稿 → 预览 → 发布」三步，没有编辑和删除接口：
 * 已发布试卷可能已被考试和答卷引用，直接改动会破坏历史成绩的可追溯性。
 * 需要调整时应重新组一份新试卷。
 *
 * <p>另有两个辅助接口：{@code POST /auto-compose} 按规则抽题生成方案（<b>只生成不写库</b>，
 * 保存仍走 {@code POST /papers}），{@code GET /{id}/export} 把试卷导成 Markdown 或可回导题库的 CSV。
 */
@RestController
@RequestMapping("/api/v1/papers")
public class PaperController {
    private final ExamService service;
    private final PaperAutoComposeService autoCompose;
    private final PaperExportService export;

    public PaperController(ExamService service, PaperAutoComposeService autoCompose, PaperExportService export) {
        this.service = service; this.autoCompose = autoCompose; this.export = export;
    }

    /** 查询本人创建的试卷。 */
    @GetMapping public ApiResponse<List<PaperView>> list(@AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.papers(userId(jwt))); }
    /** 创建试卷草稿并选题，同时校验分值合计等于总分。 */
    @PostMapping public ApiResponse<PaperView> create(@Valid @RequestBody PaperRequest request, @AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.createPaper(request, userId(jwt))); }
    /** 预览试卷。返回的题目为快照内容，且不含标准答案。 */
    @GetMapping("/{id}") public ApiResponse<PaperView> get(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.paper(id, userId(jwt))); }
    /** 发布试卷。仅草稿可发布，发布后才能用于创建考试。 */
    @PostMapping("/{id}/publish") public ApiResponse<PaperView> publish(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) { return ApiResponse.of(service.publishPaper(id, userId(jwt))); }

    /**
     * 按规则自动抽题，返回一份组卷方案。
     *
     * <p>本接口<b>不写任何数据</b>：返回的题目由前端填进组卷页，教师确认后仍走
     * {@code POST /papers} 保存，因此分值合计、题目归属、启用状态这些校验只有一处实现。
     * 反复调用会得到不同的随机结果，相当于「换一批」。
     */
    @PostMapping("/auto-compose")
    public ApiResponse<AutoComposePlan> autoCompose(@Valid @RequestBody AutoComposeRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(autoCompose.compose(request, userId(jwt)));
    }

    /**
     * 导出试卷。
     *
     * <p>响应体是纯文本文件而不是统一的 {@code ApiResponse} 包装：它要被浏览器当附件下载，
     * 套一层 JSON 反而需要前端再解一次。与题库导入模板下载是同一种处理方式。
     *
     * @param format      {@code md}（可打印试卷，默认）或 {@code csv}（列名与题库导入模板一致，可回导）
     * @param withAnswers 是否附标准答案与解析；默认 {@code false}，对 CSV 无效（CSV 必然含答案）
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<String> export(@PathVariable long id,
            @RequestParam(defaultValue = "md") String format,
            @RequestParam(defaultValue = "false") boolean withAnswers,
            @AuthenticationPrincipal Jwt jwt) {
        PaperExportService.Export file = export.export(id, format, withAnswers, userId(jwt));
        // 文件名含中文，用 ContentDisposition 按 RFC 5987 编码成 filename*=UTF-8''…，
        // 直接拼字符串会在部分客户端上得到一串乱码文件名。
        return ResponseEntity.ok()
                .header("Content-Type", file.contentType())
                .header("Content-Disposition", ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(file.content());
    }

    /** 从令牌读取当前教师 ID。 */
    private long userId(Jwt jwt) { return ((Number) jwt.getClaim("userId")).longValue(); }
}
