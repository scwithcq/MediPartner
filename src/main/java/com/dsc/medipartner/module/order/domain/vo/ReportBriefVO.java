package com.dsc.medipartner.module.order.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 报告状态摘要（契约 §5.4 的 {@code report}）：详情页只需状态做入口角标，
 * 正文走 §5.10 独立接口。
 */
@Data
public class ReportBriefVO {

    /** PROCESSING / DONE / FAILED */
    private String status;
    private LocalDateTime generatedAt;
}
