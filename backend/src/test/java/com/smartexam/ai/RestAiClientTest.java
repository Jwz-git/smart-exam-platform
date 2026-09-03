package com.smartexam.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.smartexam.common.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 协议适配层的单元测试：用 {@link MockRestServiceServer} 断言两种协议的请求形状，全程不联网。
 *
 * <p>这里验证的正是最容易配错、也最难从业务测试里看出来的三件事：请求路径、鉴权头、
 * 以及从哪个字段取回文本。换服务商时如果这三处对不上，表现是「AI 一直返回空」而不是明确报错。
 */
class RestAiClientTest {
    /** OpenAI 兼容服务的最小成功响应。 */
    private static final String OPENAI_BODY = "{\"choices\":[{\"message\":{\"content\":\"生成的题目\"}}]}";
    /** Anthropic 的最小成功响应。 */
    private static final String ANTHROPIC_BODY = "{\"content\":[{\"type\":\"text\",\"text\":\"生成的题目\"}]}";

    /**
     * OpenAI 兼容协议：请求 {@code {baseUrl}/chat/completions}，带 Bearer 头，
     * 系统提示作为第一条 system 消息，从 {@code choices[0].message.content} 取文本。
     */
    @Test void speaksOpenAiProtocol() {
        AiProperties properties = properties("openai", "https://ai.example.com/v1/");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ai.example.com/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.max_tokens").value(512))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("系统提示"))
                .andExpect(jsonPath("$.messages[1].content").value("用户提示"))
                .andRespond(withSuccess(OPENAI_BODY, MediaType.APPLICATION_JSON));

        String text = new RestAiClient(properties, builder.build()).complete("系统提示", "用户提示");

        assertEquals("生成的题目", text);
        server.verify();
    }

    /**
     * Anthropic 协议：请求 {@code {baseUrl}/v1/messages}，带 {@code x-api-key} 与
     * {@code anthropic-version}，系统提示是顶层 {@code system} 字段，从 {@code content[0].text} 取文本。
     */
    @Test void speaksAnthropicProtocol() {
        AiProperties properties = properties("anthropic", "https://claude.example.com");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://claude.example.com/v1/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "test-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(jsonPath("$.system").value("系统提示"))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("用户提示"))
                .andRespond(withSuccess(ANTHROPIC_BODY, MediaType.APPLICATION_JSON));

        String text = new RestAiClient(properties, builder.build()).complete("系统提示", "用户提示");

        assertEquals("生成的题目", text);
        server.verify();
    }

    /**
     * 未配置密钥时直接返回 503，且一个请求都不发。
     *
     * <p>这是「AI 故障不阻塞主流程」的第一道保障：没配密钥不是错误状态，
     * 只是这个功能不可用，错误信息必须让教师一眼看懂而不是看到一个 401。
     */
    @Test void failsFastWhenApiKeyMissing() {
        AiProperties properties = new AiProperties("openai", "https://ai.example.com/v1", "test-model", "  ", 5, 512);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        DomainException exception = assertThrows(DomainException.class,
                () -> new RestAiClient(properties, builder.build()).complete("系统提示", "用户提示"));

        assertEquals("AI_NOT_CONFIGURED", exception.code());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.status());
        // 没有任何请求发出，因此这里不需要 verify 任何期望。
        server.verify();
    }

    /** 服务商返回错误状态时转成 502，并把状态码带进提示，但不回显响应体。 */
    @Test void mapsUpstreamErrorToBadGateway() {
        AiProperties properties = properties("openai", "https://ai.example.com/v1");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ai.example.com/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"message\":\"invalid key\"}}"));

        DomainException exception = assertThrows(DomainException.class,
                () -> new RestAiClient(properties, builder.build()).complete("系统提示", "用户提示"));

        assertEquals("AI_REQUEST_FAILED", exception.code());
        assertEquals(HttpStatus.BAD_GATEWAY, exception.status());
        assertTrue(exception.getMessage().contains("401"), exception.getMessage());
        // 上游响应体只进日志，不进对外错误信息。
        assertTrue(!exception.getMessage().contains("invalid key"), exception.getMessage());
        server.verify();
    }

    /** 基础地址末尾的斜杠被忽略，避免拼出 {@code //chat/completions}。 */
    @Test void normalizesBaseUrlAndProtocolFlag() {
        assertEquals("https://a.example.com/v1", properties("openai", "https://a.example.com/v1///").normalizedBaseUrl());
        assertTrue(properties("Anthropic", "https://a.example.com").anthropic());
        assertTrue(!properties("openai", "https://a.example.com").anthropic());
    }

    /** 造一组测试配置。密钥是假值，不指向任何真实服务。 */
    private AiProperties properties(String protocol, String baseUrl) {
        return new AiProperties(protocol, baseUrl, "test-model", "test-key", 5, 512);
    }
}
