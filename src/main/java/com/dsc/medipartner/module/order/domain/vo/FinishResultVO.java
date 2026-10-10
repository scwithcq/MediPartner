package com.dsc.medipartner.module.order.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 结束服务结果（契约 §7.14 响应）：reportStatus 恒为 PROCESSING，
 * 报告由 MQ 异步生成，前端拿 orderNo 轮询 §5.10。
 */
@Data
public class FinishResultVO {

    private String orderNo;
    /** 结束后的订单状态，恒为 40 待确认 */
    private Integer orderStatus;
    private LocalDateTime finishedAt;
    /** PROCESSING / DONE / FAILED */
    private String reportStatus;
}
