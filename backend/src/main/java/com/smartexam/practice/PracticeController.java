package com.smartexam.practice;

import com.smartexam.common.ApiResponse;
import com.smartexam.practice.PracticeModels.PracticeResultView;
import com.smartexam.practice.PracticeModels.PracticeSetView;
import com.smartexam.practice.PracticeModels.PracticeSubmitRequest;
import com.smartexam.practice.PracticeModels.WrongBookView;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 错题本与错题重练接口，仅学生本人可访问。
 *
 * <p>路径挂在 {@code /api/v1/my/**} 下，与「我的成绩」同一套约定：<b>路径里不带学生 ID</b>，
 * 范围一律取自令牌。这样就不存在「把 URL 里的 ID 改成别人」这类越权，
 * {@code SecurityConfig} 里该前缀也已限定为 {@code STUDENT}，教师和管理员访问会得到 403。
 */
@RestController
@RequestMapping("/api/v1/my/wrong-questions")
public class PracticeController {
    private final PracticeService service;

    public PracticeController(PracticeService service) { this.service = service; }

    /** 错题本：本人在已公布成绩的考试里未得满分的题目，含标准答案、解析与教师评语。 */
    @GetMapping
    public ApiResponse<WrongBookView> wrongBook(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.wrongBook(userId(jwt)));
    }

    /**
     * 取一组重练题目。
     *
     * <p>响应里<b>没有标准答案和解析</b>，也没有上次的错误作答——否则重练等于抄答案。
     *
     * @param size           想练几道；缺省用系统设置里的每组题数，且不能超过它
     * @param onlyUnmastered 是否只练还没练对的题，缺省为 true
     */
    @GetMapping("/practice")
    public ApiResponse<PracticeSetView> practiceSet(@RequestParam(required = false) Integer size,
            @RequestParam(defaultValue = "true") boolean onlyUnmastered,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.practiceSet(userId(jwt), size, onlyUnmastered));
    }

    /**
     * 提交重练作答并即时判分。
     *
     * <p>判分复用交卷时的同一套客观题比较规则；练习记录写入独立的表，
     * <b>不改动答卷、得分和排名</b>。返回结果时才给出标准答案与解析。
     */
    @PostMapping("/practice")
    public ApiResponse<PracticeResultView> practice(@Valid @RequestBody PracticeSubmitRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.submit(userId(jwt), request));
    }

    /** 从令牌读取当前学生 ID。 */
    private long userId(Jwt jwt) { return ((Number) jwt.getClaim("userId")).longValue(); }
}
