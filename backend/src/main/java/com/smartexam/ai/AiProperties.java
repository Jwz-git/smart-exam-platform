package com.smartexam.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 服务商配置。全部来自后端环境变量，任何一项都不下发浏览器。
 *
 * <p>刻意做成「协议 + 基础地址 + 模型 + 密钥」四件事，而不是绑定某一家服务商：
 * 课程演示可能用 DeepSeek，答辩现场可能改用通义、Kimi 或本机的 Ollama，
 * 换服务商时只改这几个环境变量，业务代码一行不动。
 *
 * <p>{@code baseUrl} 是否包含版本前缀取决于协议，这一点容易配错，因此在 {@code .env.example}
 * 和 {@code docs/api.md} 里都写明了：
 * <ul>
 *   <li>{@code openai} 协议：地址要带到版本段，例如 {@code https://api.deepseek.com/v1}，
 *       实际请求是 {@code {baseUrl}/chat/completions}；</li>
 *   <li>{@code anthropic} 协议：地址只到域名，例如 {@code https://api.anthropic.com}，
 *       实际请求是 {@code {baseUrl}/v1/messages}。</li>
 * </ul>
 *
 * @param protocol       协议，{@code openai} 或 {@code anthropic}，大小写不敏感
 * @param baseUrl        服务基础地址，末尾多余的斜杠会被忽略
 * @param model          模型名，直接透传给服务商
 * @param apiKey         API 密钥；为空表示未配置 AI，出题接口会返回可读错误而不影响其他功能
 * @param timeoutSeconds 读超时秒数。出题是一次较长的生成，默认给到 60 秒
 * @param maxTokens      单次生成的最大 token 数，避免模型无限输出
 */
@ConfigurationProperties("app.ai")
public record AiProperties(String protocol, String baseUrl, String model, String apiKey,
        int timeoutSeconds, int maxTokens) {

    /** 是否已配置可用的密钥。未配置时 AI 出题不可用，但手工出题和考试主流程必须照常工作。 */
    public boolean configured() { return apiKey != null && !apiKey.isBlank(); }

    /** 去掉末尾斜杠后的基础地址，避免拼出 {@code //chat/completions} 这样的路径。 */
    public String normalizedBaseUrl() {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    /** 是否为 Anthropic Messages 协议；其余取值一律按 OpenAI 兼容协议处理。 */
    public boolean anthropic() { return "anthropic".equalsIgnoreCase(protocol == null ? "" : protocol.trim()); }
}
