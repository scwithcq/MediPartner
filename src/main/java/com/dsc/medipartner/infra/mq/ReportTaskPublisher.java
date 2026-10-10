package com.dsc.medipartner.infra.mq;

/**
 * AI 报告任务发布器：medi.mq.type=async 走进程内线程池（默认），=rabbit 走 AMQP。
 * 与 ParseTaskPublisher 同构，订单结束服务（§7.14）后由 OrderServiceImpl 在事务提交后调用。
 */
public interface ReportTaskPublisher {

    void publishReportTask(String orderNo);
}
