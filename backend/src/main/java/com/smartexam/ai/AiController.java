package com.smartexam.ai;

import com.smartexam.ai.AiModels.DraftRequest;
import com.smartexam.ai.AiModels.DraftResponse;
import com.smartexam.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 辅助出题接口，仅教师可访问（角色限制在 {@code SecurityConfig} 里按 URL 前缀配置）。
 *
 * <p>只有「生成草稿」一个动作，没有「保存」——保存一律走 {@code POST /api/v1/questions}。
 * 这样 AI 无法绕过任何业务校验，也不可能在教师没看过的情况下把题目写进题库。
 *
 * <p>接口不接收也不返回任何密钥：{@code AI_API_KEY} 只存在于后端环境变量里。
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {
    private final AiService service;

    public AiController(AiService service) { this.service = service; }

    /**
     * 生成题目草稿，不写库。
     *
     * <p>用 POST 而不是 GET：请求带有补充要求这类较长的正文，且每次调用都会消耗模型额度，
     * 语义上不是可缓存的安全请求。
     */
    @PostMapping("/question-drafts")
    public ApiResponse<DraftResponse> drafts(@Valid @RequestBody DraftRequest request) {
        return ApiResponse.of(service.generate(request));
    }
}
