package com.dsc.medipartner.infra.mq;

/**
 * 文档解析任务发布器：medi.mq.type=async 走进程内线程池（默认），=rabbit 走 AMQP。
 */
public interface ParseTaskPublisher {

    void publishParseTask(Long docId);
}
