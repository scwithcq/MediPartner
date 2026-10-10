package com.dsc.medipartner.module.wallet.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 模拟支付回调（契约 §3.9）。
 * {@code result} 取 {@code SUCCESS} / {@code FAILED}。
 */
@Data
public class RechargeCallbackRequest {

    @NotBlank(message = "充值单号不能为空")
    private String rechargeNo;

    @NotBlank(message = "支付结果不能为空")
    private String result;
}
