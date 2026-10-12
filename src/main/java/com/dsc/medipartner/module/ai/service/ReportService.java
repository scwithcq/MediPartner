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

    /**
     * 只读查询报告状态摘要，供 §5.4 订单详情的 {@code report} 键使用（阶段 6 新增）。
     *
     * <p><b>为什么不能复用 </b>{@code getReport}：后者<b>有副作用</b>——无报告行时会建行、
     * 投递 AI 生成任务、把 FAILED 翻回 PROCESSING。订单详情是高频轮询接口（建议 15s 一次），
     * 复用它会导致「每次打开详情都触发一次 AI 任务」，既烧钱又污染状态。
     *
     * <p>本方法<b>绝不写库、绝不投递任务</b>：没有报告行就返回 {@code null}，
     * 由调用方决定不下发该键（契约 §5.4「无报告时键缺失」）。
     *
     * @return {@code { status, generatedAt }}；无报告行时返回 {@code null}
     */
    com.dsc.medipartner.module.order.domain.vo.ReportBriefVO getReportBrief(String orderNo);
}
