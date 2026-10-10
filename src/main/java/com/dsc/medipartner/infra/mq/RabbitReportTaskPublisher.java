package com.dsc.medipartner.infra.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 实现：报告任务发往 medi.knowledge.exchange（rk=report.generate）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "medi.mq.type", havingValue = "rabbit")
public class RabbitReportTaskPublisher implements ReportTaskPublisher {

    private final RabbitTemplate rabbitTemplate;

    public RabbitReportTaskPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publishReportTask(String orderNo) {
        log.info("[MQ-rabbit] 投递服务报告生成任务 orderNo={}", orderNo);
        rabbitTemplate.convertAndSend(RabbitMqConfig.EXCHANGE, RabbitMqConfig.REPORT_ROUTING_KEY,
                new ReportGenerateMessage(orderNo));
    }
}
