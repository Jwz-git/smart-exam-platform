package com.smartexam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 智能在线题库与组卷系统的后端入口。
 *
 * <p>业务主流程为：教师维护题库 → 手动组卷并发布试卷 → 创建并发布考试 → 学生在线答题并交卷 →
 * 系统自动判客观题 → 教师批阅主观题 → 公布并查询成绩。各环节的模块划分见 {@code docs/technical-design.md}。
 *
 * <p>{@link EnableScheduling} 用于开启定时任务扫描：到达截止时间但仍在作答的答卷需要由服务端
 * 自动交卷并判分，这个机制不能依赖学生浏览器保持打开，详见
 * {@link com.smartexam.exam.ExamService#autoSubmitExpired()}。触发器
 * {@link com.smartexam.exam.ExamScheduler} 只在 Web 模式下注册——非 Web 模式（跑迁移用）
 * 若注册了定时任务，非守护的调度线程会让进程无法退出。
 *
 * <p>{@link ConfigurationPropertiesScan} 用于绑定 {@link com.smartexam.ai.AiProperties}：
 * AI 服务商的协议、地址、模型和密钥全部来自环境变量，不硬编码在业务代码里。
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class SmartExamApplication {
    public static void main(String[] args) {
        SpringApplication.run(SmartExamApplication.class, args);
    }
}
