package com.smartexam.system;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查接口，供部署脚本和演示前的环境自检使用。
 *
 * <p>该接口在 {@code SecurityConfig} 里显式放行，不需要登录，因此响应中只包含服务名和时间戳，
 * 不返回版本号、数据库地址等可能帮助攻击者做信息收集的内容。
 *
 * <p>返回值刻意不套 {@code ApiResponse}：探活脚本直接读顶层 {@code status} 字段更方便。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {
    @GetMapping
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "smart-exam-backend", "timestamp", Instant.now().toString());
    }
}
