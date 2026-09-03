package com.smartexam.exam;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 超时自动交卷的定时触发器。
 *
 * <p>把 {@code @Scheduled} 从 {@link ExamService} 抽到这里，只为一件事：
 * {@link ConditionalOnWebApplication} 让定时任务只在以 Web 方式启动时注册。
 *
 * <p>原因是 {@code scripts/init-local.sh} 会用
 * {@code --spring.main.web-application-type=none} 启动一次应用来跑 Flyway 迁移，跑完就该退出。
 * 而 Spring 为 {@code @Scheduled} 创建的调度线程不是守护线程：只要注册了定时任务，
 * 迁移结束后 JVM 也不会退出，一键初始化脚本会永久卡在那一步。加上这个条件之后，
 * 非 Web 模式下没有任何定时任务，容器刷新完成、Flyway 执行完毕，进程就正常结束。
 *
 * <p>Web 模式下行为完全不变：Tomcat 自己的非守护线程本来就会让进程常驻。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ExamScheduler {
    private final ExamService service;

    public ExamScheduler(ExamService service) { this.service = service; }

    /**
     * 每 30 秒扫描一次到点未交的答卷。
     *
     * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：上一轮扫描结束后才开始计时，
     * 答卷很多时不会因为一轮没跑完就叠加下一轮。间隔可用
     * {@code app.exam.auto-submit-interval-ms} 覆盖，测试里因此不必等真实的 30 秒。
     */
    @Scheduled(fixedDelayString = "${app.exam.auto-submit-interval-ms:30000}")
    public void autoSubmitExpired() {
        service.autoSubmitExpired();
    }
}
