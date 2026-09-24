package com.dsc.medipartner.infra.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 文档解析消费者：处理失败不重回队列（pipeline 内部已把失败落到 doc.status=FAILED，
 * 管理端可对 FAILED 文档重新发布触发重试），避免毒消息无限循环。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "medi.mq.type", havingValue = "rabbit")
public class DocumentParseConsumer {

    private final DocumentParseHandler handler;

    public DocumentParseConsumer(DocumentParseHandler handler) {
        this.handler = handler;
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE)
    public void onMessage(DocumentParseMessage message) {
        log.info("[MQ-rabbit] 收到文档解析任务 docId={}", message.docId());
        handler.handle(message.docId());
    }
}
