package com.smartexam.ai;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * AI 调用专用的 HTTP 客户端配置。
 *
 * <p>单独成一个配置类，是为了把「怎么建 HTTP 连接」和「怎么说协议」分开：
 * 超时这类基础设施关注点放在这里，{@link RestAiClient} 只关心请求路径、鉴权头和取文本，
 * 测试也就能给它换一个绑定了模拟服务器的客户端。
 */
@Configuration
public class AiHttpConfig {
    /** 连接超时固定 10 秒：连不上通常是地址或网络问题，等再久也没用；生成慢体现在读超时上。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 供 AI 调用使用的 {@code RestClient}。
     *
     * <p>读超时取 {@code AI_TIMEOUT_SECONDS}：一次出题生成动辄十几秒，用默认的短超时会
     * 在模型还在输出时就把连接断掉。下限兜到 1 秒，避免配成 0 导致立即超时。
     */
    @Bean
    RestClient aiRestClient(AiProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1, properties.timeoutSeconds())));
        return RestClient.builder().requestFactory(factory).build();
    }
}
