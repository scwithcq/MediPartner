package com.dsc.medipartner.module.wallet.domain.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 发起充值（契约 §3.8）。
 * 金额范围校验在服务层用配置项兜底（返回 4006），此处注解仅做快速失败。
 */
@Data
public class RechargeRequest {

    @NotNull(message = "充值金额不能为空")
    @DecimalMin(value = "0.01", message = "充值金额不能小于 0.01")
    @DecimalMax(value = "10000.00", message = "充值金额不能超过 10000.00")
    private BigDecimal amount;

    /** 预设档位码，如 C50/C100/C500，仅用于统计 */
    @Size(max = 16, message = "预设档位码长度不能超过 16")
    private String presetCode;
}
