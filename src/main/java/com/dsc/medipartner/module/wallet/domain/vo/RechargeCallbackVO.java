package com.dsc.medipartner.module.wallet.domain.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 模拟支付回调响应（契约 §3.9）。
 */
@Data
public class RechargeCallbackVO {

    private String rechargeNo;
    /** SUCCESS / FAILED */
    private String status;
    /** 入账后的余额；FAILED 时键缺失 */
    private BigDecimal balance;
    /** 充值流水号；FAILED 时键缺失 */
    private String txnNo;
}
