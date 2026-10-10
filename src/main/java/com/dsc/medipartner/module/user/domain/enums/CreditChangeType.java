package com.dsc.medipartner.module.user.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 信用分变动类型（契约 §7.16 备注 1：四类来源全部后端事件驱动）。
 * reason 为后端下发的中文短语，records[].reason 直接用，前端不做映射。
 */
@Getter
@AllArgsConstructor
public enum CreditChangeType {

    ORDER_COMPLETED(2, "按时完成订单"),
    POSITIVE_REVIEW(1, "获得好评"),
    NEGATIVE_REVIEW(-2, "收到差评"),
    NO_SHOW(-20, "爽约（申诉核实）"),
    LATE_OVER_15MIN(-5, "迟到超15分钟"),
    TRACE_TIMEOUT(-2, "未按时上传服务留痕"),
    COMPLAINT_UPHELD(-10, "投诉申诉成立"),
    ADMIN_ADJUST(0, "管理员调整");

    private final int score;
    private final String reason;
}
