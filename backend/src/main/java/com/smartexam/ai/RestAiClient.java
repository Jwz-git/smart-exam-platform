package com.smartexam.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartexam.common.DomainException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 基于 {@link RestClient} 的 AI 客户端，同时支持 OpenAI 兼容协议和 Anthropic Messages 协议。
 *
 * <p>两种协议的差异只有三处，代码里也只体现为三处：请求路径、鉴权头、取文本的字段路径。
 * 把它们并排放在本类里，换服务商时该看哪一段一目了然。
 *
 * <p>刻意不发送 {@code response_format: json_object}：OpenAI 和 DeepSeek 支持它，但
 * vLLM、Ollama 等本地兼容服务未必认这个字段，发过去可能直接 400。因此改为在提示词里
 * 要求「只输出 JSON」，再由 {@code AiService} 容错解析——牺牲一点确定性换取更广的兼容性。
 *
 * <p>本类是 {@link AiClient} 唯一的生产实现；测试用 {@code @MockitoBean} 把这个 Bean 换成桩，
 * 从而在不联网、不消耗额度的前提下验证整条链路。
 */
@Component
public class RestAiClient implements AiClient {
    private static final Logger log = LoggerFactory.getLogger(RestAiClient.class);
    /** Anthropic 要求显式声明 API 版本，缺少该头会被拒绝。 */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final AiProperties properties;
    private final RestClient client;

    /**
     * {@code RestClient} 从外部注入（见 {@link AiHttpConfig}），本类不负责构造它。
     *
     * <p>这样拆开是为了可测：测试可以传入一个绑定了 {@code MockRestServiceServer} 的客户端，
     * 从而在不联网的前提下断言两种协议的请求路径、鉴权头和请求体形状。
     * 如果超时设置写在本类构造器里，就会把测试用的模拟请求工厂覆盖掉。
     */
    public RestAiClient(AiProperties properties, RestClient client) {
        this.properties = properties; this.client = client;
    }

    /**
     * 发起补全请求并取出文本。
     *
     * <p>未配置密钥时直接返回 503 而不是发一个必然失败的请求：错误信息更清楚，
     * 也不会在服务商侧留下一堆 401 记录。
     */
    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (!properties.configured()) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED",
                    "未配置 AI 密钥，无法生成草稿；手工出题不受影响");
        }
        return properties.anthropic() ? callAnthropic(systemPrompt, userPrompt) : callOpenAi(systemPrompt, userPrompt);
    }

    /**
     * OpenAI 兼容协议：{@code POST {baseUrl}/chat/completions}，读 {@code choices[0].message.content}。
     *
     * <p>系统提示作为 {@code role: system} 的第一条消息，这是 OpenAI 协议的约定；
     * DeepSeek、通义、Kimi、vLLM、Ollama 都遵循同一形状。
     */
    private String callOpenAi(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", properties.model(),
                "max_tokens", properties.maxTokens(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        JsonNode response = post(properties.normalizedBaseUrl() + "/chat/completions", body,
                builder -> builder.header("Authorization", "Bearer " + properties.apiKey()));
        return response.at("/choices/0/message/content").asText("");
    }

    /**
     * Anthropic Messages 协议：{@code POST {baseUrl}/v1/messages}，读 {@code content[0].text}。
     *
     * <p>与 OpenAI 的两个关键差异：系统提示是顶层的 {@code system} 字段而不是一条消息，
     * 鉴权用 {@code x-api-key} 而不是 {@code Authorization: Bearer}。
     */
    private String callAnthropic(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", properties.model(),
                "max_tokens", properties.maxTokens(),
                "system", systemPrompt,
                "messages", List.of(Map.of("role", "user", "content", userPrompt)));
        JsonNode response = post(properties.normalizedBaseUrl() + "/v1/messages", body,
                builder -> builder.header("x-api-key", properties.apiKey())
                        .header("anthropic-version", ANTHROPIC_VERSION));
        return response.at("/content/0/text").asText("");
    }

    /**
     * 发送 POST 并把各类失败转成可读的业务异常。
     *
     * <p>服务商返回的响应体只写进服务端日志，不放进接口响应：它可能包含请求回显，
     * 而请求头里带着密钥。返回给前端的只有状态码和一句中文说明。
     * 日志同样先做一次掩码，防止密钥出现在日志文件里。
     */
    private JsonNode post(String url, Map<String, Object> body,
            java.util.function.UnaryOperator<RestClient.RequestBodySpec> headers) {
        try {
            RestClient.RequestBodySpec spec = client.post().uri(url).contentType(MediaType.APPLICATION_JSON);
            JsonNode response = headers.apply(spec).body(body).retrieve().body(JsonNode.class);
            if (response == null) {
                throw new DomainException(HttpStatus.BAD_GATEWAY, "AI_EMPTY_RESPONSE", "AI 服务返回空响应，请重试");
            }
            return response;
        } catch (RestClientResponseException exception) {
            log.warn("AI 服务返回错误状态 {}：{}", exception.getStatusCode(), mask(exception.getResponseBodyAsString()));
            throw new DomainException(HttpStatus.BAD_GATEWAY, "AI_REQUEST_FAILED",
                    "AI 服务返回 " + exception.getStatusCode().value() + "，请检查密钥、模型名与配额");
        } catch (RestClientException exception) {
            log.warn("AI 服务调用失败：{}", mask(String.valueOf(exception.getMessage())));
            throw new DomainException(HttpStatus.BAD_GATEWAY, "AI_REQUEST_FAILED",
                    "AI 服务连接失败或超时，请稍后重试；手工出题不受影响");
        }
    }

    /** 把日志文本里的密钥替换掉，避免密钥随异常信息落到日志文件。 */
    private String mask(String text) {
        String key = properties.apiKey();
        String limited = text.length() > 500 ? text.substring(0, 500) + "…" : text;
        return key == null || key.isBlank() ? limited : limited.replace(key, "***");
    }
}
