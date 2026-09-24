package com.dsc.medipartner.infra.mq;

import java.io.Serializable;

/**
 * 文档解析任务消息体（RabbitMQ JSON 序列化，需可序列化契约稳定）。
 */
public record DocumentParseMessage(Long docId) implements Serializable {
}
