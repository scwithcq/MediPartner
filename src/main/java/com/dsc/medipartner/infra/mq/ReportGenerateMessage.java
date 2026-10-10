package com.dsc.medipartner.infra.mq;

import java.io.Serializable;

/**
 * AI 报告生成任务消息体（RabbitMQ JSON 序列化，需可序列化契约稳定）。
 */
public record ReportGenerateMessage(String orderNo) implements Serializable {
}
