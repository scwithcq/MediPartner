package com.dsc.medipartner.infra.mq;

/**
 * AI 报告生成任务的实际处理入口，由 ReportServiceImpl 实现；
 * MQ 组件只依赖本接口，避免 infra 反向依赖业务模块（与 DocumentParseHandler 同构）。
 */
public interface ReportGenerateHandler {

    /** 按订单号汇总留痕/小结生成服务报告，失败落 ai_report.status=FAILED 并累加 retry_count */
    void handle(String orderNo);
}
