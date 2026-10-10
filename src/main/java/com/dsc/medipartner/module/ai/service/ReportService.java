package com.dsc.medipartner.module.ai.service;

import com.dsc.medipartner.module.ai.domain.vo.ReportVO;

/**
 * AI 陪诊报告读取服务（契约 §5.10）。
 * 报告由订单结束服务（§7.14）后异步生成，本接口负责用户轮询读取与首次触发。
 */
public interface ReportService {

    /**
     * 读取订单的 AI 报告：
     * 归属校验 3008、订单未结束（status<40）3002；
     * 无报告行则建 PROCESSING 并投递生成任务，返回 PROCESSING（code 0）；
     * DONE 返回完整正文；FAILED 且重试未耗尽则重投并返回 PROCESSING，耗尽后暴露 failReason。
     */
    ReportVO getReport(Long userId, String orderNo);
}
