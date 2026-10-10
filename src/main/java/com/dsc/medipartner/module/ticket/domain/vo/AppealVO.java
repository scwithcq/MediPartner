package com.dsc.medipartner.module.ticket.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 申诉进度（契约 §5.15）。
 * <b>从未申诉过时返回 {@code code=0} + {@code data} 为空对象 {@code {}}</b>，
 * 由 Controller 返回 null 对象实现（契约要求：避免前端把正常态当错误）。
 */
@Data
public class AppealVO {

    private String appealNo;
    /** PENDING 处理中 / PROCESSING 仲裁中 / CLOSED 已结案 */
    private String status;
    private String type;
    private String typeName;
    private String reason;
    private List<String> evidenceUrls;
    /** UPHOLD / PARTIAL_REFUND / FULL_REFUND（CLOSED 时有值） */
    private String arbitrateResult;
    private BigDecimal refundAmount;
    /** 处理说明（对用户可见措辞） */
    private String adminRemark;
    private LocalDateTime handleTime;
    private LocalDateTime createdAt;
}
