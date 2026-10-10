package com.dsc.medipartner.module.ai.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务报告生成的统一入参：由 ReportServiceImpl 从订单 + 留痕组装，
 * 供 ReportPromptBuilder（真实模型）与 DegradedReportGenerator（降级模板）共用。
 * traces 已按 seq 升序，node 为服务节点码值，type 为 TRACE/VOUCHER。
 */
public record ReportInput(String orderNo,
                          String symptomDesc,
                          String city,
                          String hospitalPref,
                          String serviceType,
                          LocalDateTime serviceTime,
                          String serviceSummary,
                          BigDecimal actualHours,
                          BigDecimal amount,
                          List<TraceLine> traces) {

    /** 单条留痕的精简投影，避免 module/ai 直接依赖 order 实体 */
    public record TraceLine(String type,
                            String node,
                            LocalDateTime watermarkTime,
                            String address,
                            String remark) {
    }
}
