package com.dsc.medipartner.module.wallet.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 提现单（契约 §7.18 响应 / §7.19 列表元素）。
 * 未发生的字段（驳回原因、打款时间）不返回——由 {@code non_null} 序列化自动省略。
 */
@Data
public class WithdrawVO {

    private String withdrawNo;
    private BigDecimal amount;
    /** 手续费，本项目恒为 0.00 */
    private BigDecimal fee;
    /** 实际到账 = amount - fee */
    private BigDecimal actualAmount;
    /** WECHAT / BANK */
    private String channel;
    /** PENDING / SUCCESS / REJECT */
    private String status;
    /** 驳回原因（§7.19 列表额外字段） */
    private String auditRemark;
    private LocalDateTime auditTime;
    /** 打款完成时间 */
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
}
