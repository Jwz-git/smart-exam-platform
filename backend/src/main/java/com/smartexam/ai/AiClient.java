package com.smartexam.ai;

/**
 * 大模型文本补全的最小抽象。
 *
 * <p>只有一个方法，且只做「发请求、取文本」两件事——协议差异、鉴权头和超时都在实现里，
 * 提示词与 JSON 解析都在 {@code AiService} 里。这样测试可以换一个返回固定文本的实现，
 * 既不消耗真实额度，也不会因为模型输出变化导致测试时红时绿。
 */
public interface AiClient {
    /**
     * 发起一次补全请求。
     *
     * @param systemPrompt 系统提示，约定角色与输出格式
     * @param userPrompt   本次出题的具体要求
     * @return 模型输出的纯文本；调用方负责从中解析 JSON
     */
    String complete(String systemPrompt, String userPrompt);
}
