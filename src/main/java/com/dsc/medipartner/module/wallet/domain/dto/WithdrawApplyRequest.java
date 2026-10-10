package com.dsc.medipartner.module.wallet.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 申请提现（契约 §7.18）。
 * 上下限由 {@code medi.wallet.min-withdraw}/{@code max-withdraw} 配置决定，越界返回 4003。
 */
@Data
public class WithdrawApplyRequest {

    @NotNull(message = "提现金额不能为空")
    private BigDecimal amount;

    /** WECHAT（唯一实现）/ BANK（预留，传入返回 1001） */
    @NotBlank(message = "提现渠道不能为空")
    private String channel;

    @Size(max = 100, message = "备注长度不能超过 100")
    private String remark;
}
