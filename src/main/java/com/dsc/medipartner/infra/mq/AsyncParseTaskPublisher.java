package com.dsc.medipartner.infra.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 默认实现：RabbitMQ 未启动时的进程内异步替代，任务提交到 parseExecutor 线程池。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "medi.mq.type", havingValue = "async", matchIfMissing = true)
public class AsyncParseTaskPublisher implements ParseTaskPublisher {

    private final DocumentParseHandler handler;

    public AsyncParseTaskPublisher(DocumentParseHandler handler) {
        this.handler = handler;
    }

    @Override
    @Async("parseExecutor")
    public void publishParseTask(Long docId) {
        log.info("[MQ-async] 开始异步解析文档 docId={}", docId);
        handler.handle(docId);
    }
}
