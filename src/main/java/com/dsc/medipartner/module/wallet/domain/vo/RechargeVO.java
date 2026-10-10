package com.dsc.medipartner.module.wallet.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 发起充值响应（契约 §3.8）。
 */
@Data
public class RechargeVO {

    private String rechargeNo;
    private BigDecimal amount;
    /** 固定 PENDING */
    private String status;
    /**
     * 模拟支付参数。本项目为 {@code {"mock": true}}；
     * 接真实微信支付时替换为 {@code timeStamp/nonceStr/package/signType/paySign}，契约不变。
     */
    private Map<String, Object> payParams;
    /** 支付超时时间（默认 15 分钟） */
    private LocalDateTime expireAt;
}
