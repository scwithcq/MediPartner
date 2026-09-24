package com.dsc.medipartner.infra.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 实现：任务发往 medi.knowledge.exchange（rk=doc.parse）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "medi.mq.type", havingValue = "rabbit")
public class RabbitParseTaskPublisher implements ParseTaskPublisher {

    private final RabbitTemplate rabbitTemplate;

    public RabbitParseTaskPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publishParseTask(Long docId) {
        log.info("[MQ-rabbit] 投递文档解析任务 docId={}", docId);
        rabbitTemplate.convertAndSend(RabbitMqConfig.EXCHANGE, RabbitMqConfig.ROUTING_KEY,
                new DocumentParseMessage(docId));
    }
}
