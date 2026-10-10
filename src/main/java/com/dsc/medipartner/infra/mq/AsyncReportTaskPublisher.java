package com.dsc.medipartner.infra.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 默认实现：RabbitMQ 未启动时的进程内异步替代，任务提交到 parseExecutor 线程池。
 * 用 ObjectProvider 延迟解析 ReportGenerateHandler：ReportServiceImpl 会注入本发布器做重试，
 * 直接构造器注入 handler 会构成循环依赖。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "medi.mq.type", havingValue = "async", matchIfMissing = true)
public class AsyncReportTaskPublisher implements ReportTaskPublisher {

    private final ObjectProvider<ReportGenerateHandler> handlerProvider;

    public AsyncReportTaskPublisher(ObjectProvider<ReportGenerateHandler> handlerProvider) {
        this.handlerProvider = handlerProvider;
    }

    @Override
    @Async("parseExecutor")
    public void publishReportTask(String orderNo) {
        ReportGenerateHandler handler = handlerProvider.getIfAvailable();
        if (handler == null) {
            log.warn("[MQ-async] 未找到报告生成处理器，跳过 orderNo={}", orderNo);
            return;
        }
        log.info("[MQ-async] 开始异步生成服务报告 orderNo={}", orderNo);
        handler.handle(orderNo);
    }
}
