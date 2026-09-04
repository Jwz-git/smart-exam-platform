package com.smartexam.question;

import com.smartexam.common.ApiResponse;
import com.smartexam.common.PageResult;
import com.smartexam.question.QuestionImportModels.ImportRequest;
import com.smartexam.question.QuestionImportModels.ImportResult;
import com.smartexam.question.QuestionModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 题目管理接口，仅教师可访问（角色限制在 {@code SecurityConfig} 里按 URL 前缀配置）。
 *
 * <p>{@link Validated} 让方法参数上的 {@code @Min}、{@code @Max} 生效；校验失败抛出的
 * {@code ConstraintViolationException} 由全局处理器转成 400，而不是 500。
 *
 * <p>本类只做参数绑定和响应包装，权限、归属和业务校验都在 {@code QuestionService} 里。
 */
@Validated
@RestController
@RequestMapping("/api/v1/questions")
public class QuestionController {
    private final QuestionService service;
    private final QuestionImportService importService;

    public QuestionController(QuestionService service, QuestionImportService importService) {
        this.service = service; this.importService = importService;
    }

    /**
     * 分页查询本人题目，支持关键词、题型、难度、知识点和状态筛选。
     *
     * <p>{@code size} 上限 100，避免一次请求把整个题库拉走；不传 {@code status} 时返回全部状态。
     */
    @GetMapping
    public ApiResponse<PageResult<QuestionView>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) String keyword,
            @RequestParam(required=false) Type type,
            @RequestParam(required=false) Difficulty difficulty,
            @RequestParam(required=false) Long knowledgePointId,
            @RequestParam(required=false) String status,
            @RequestParam(defaultValue="1") @Min(1) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return ApiResponse.of(service.list(userId(jwt), keyword, type, difficulty, knowledgePointId, status, page, size));
    }

    /** 新增题目。题型与标准答案的匹配关系由 Service 校验。 */
    @PostMapping
    public ApiResponse<QuestionView> create(@Valid @RequestBody QuestionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.create(request, userId(jwt)));
    }

    /** 查询题目详情，含选项和标准答案；只能查本人创建的题目。 */
    @GetMapping("/{id}")
    public ApiResponse<QuestionView> get(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.get(id, userId(jwt)));
    }

    /**
     * 编辑题目。
     *
     * <p>修改不会影响已有试卷：题目加入试卷时已在 {@code paper_question} 留了题干、选项和答案快照，
     * 历史试卷和答卷始终按当时的快照判分。
     */
    @PutMapping("/{id}")
    public ApiResponse<QuestionView> update(@PathVariable long id, @Valid @RequestBody QuestionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.update(id, request, userId(jwt)));
    }

    /** 启用或停用题目。停用后不能加入新试卷，但历史试卷快照不受影响。 */
    @PatchMapping("/{id}/status")
    public ApiResponse<QuestionView> updateStatus(@PathVariable long id, @Valid @RequestBody StatusRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.setStatus(id, request.status(), userId(jwt)));
    }

    /** 删除题目：未被任何试卷引用时物理删除，已被引用时降级为停用以保留历史数据。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        service.delete(id, userId(jwt));
        return ResponseEntity.noContent().build();
    }

    /**
     * 批量导入题库：预览或写入。
     *
     * <p>请求体是<b>表格文本</b>而不是 multipart 文件。这样后端不必处理上传大小、临时文件和
     * 文件名编码，前端读文件与直接粘贴表格共用同一个接口，行为完全一致；
     * 代价是导入内容必须能放进一个 JSON 请求体，因此长度上限写在了请求模型上。
     *
     * <p>{@code dryRun} 不传按预览处理，写库必须显式传 {@code false}——
     * 漏传参数不应该导致几十道题直接进库。
     *
     * <p>路径放在 {@code /questions} 下面是为了继承 {@code SecurityConfig} 里
     * 「{@code /api/v1/questions/**} 仅教师」的规则，不需要额外的方法级注解。
     */
    @PostMapping("/import")
    public ApiResponse<ImportResult> importQuestions(@Valid @RequestBody ImportRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(importService.run(request, userId(jwt)));
    }

    /**
     * 下载导入模板。
     *
     * <p>返回 {@code text/csv} 而不是包在 {@code data} 里的 JSON：这是一份要交给 Excel 打开的文件。
     * 正文带 UTF-8 BOM，否则 Excel 会把中文表头显示成乱码。模板由解析器所在的类生成，
     * 保证「模板里的表头」与「解析器认识的列名」不会各自漂移。
     */
    @GetMapping(value = "/import/template", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<String> importTemplate() {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"question-import-template.csv\"")
                .body(importService.template());
    }

    /**
     * 从令牌里取出当前教师的用户 ID。
     *
     * <p>先转成 {@link Number} 再取 {@code longValue()}：JSON 数字经过解析后可能是 Integer 或 Long，
     * 直接强转成某一种会在边界情况抛 ClassCastException。
     */
    private long userId(Jwt jwt) { return ((Number) jwt.getClaim("userId")).longValue(); }

    /** 状态变更请求，取值为 {@code ACTIVE} 或 {@code DISABLED}。 */
    public record StatusRequest(@jakarta.validation.constraints.NotBlank String status) {}
}
