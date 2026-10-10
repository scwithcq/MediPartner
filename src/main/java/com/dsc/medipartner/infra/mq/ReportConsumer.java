package com.dsc.medipartner.infra.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 服务报告消费者：处理失败不重回队列（ReportServiceImpl 内部已把失败落到 ai_report.status=FAILED，
 * 用户轮询 §5.10 时按 retry_count 决定是否重投），避免毒消息无限循环。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "medi.mq.type", havingValue = "rabbit")
public class ReportConsumer {

    private final ReportGenerateHandler handler;

    public ReportConsumer(ReportGenerateHandler handler) {
        this.handler = handler;
    }

    @RabbitListener(queues = RabbitMqConfig.REPORT_QUEUE)
    public void onMessage(ReportGenerateMessage message) {
        log.info("[MQ-rabbit] 收到服务报告生成任务 orderNo={}", message.orderNo());
        handler.handle(message.orderNo());
    }
}
