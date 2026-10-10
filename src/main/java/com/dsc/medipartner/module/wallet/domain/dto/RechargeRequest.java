package com.dsc.medipartner.module.wallet.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 发起充值（契约 §3.8）。
 *
 * <p><b>金额范围不在这里用 Bean Validation 校验</b>：契约 §3.8 要求金额越界返回
 * <b>4006 RECHARGE_AMOUNT_ILLEGAL</b>，而 {@code @DecimalMin/@DecimalMax} 会被
 * {@code GlobalExceptionHandler} 统一转成 1001 PARAM_ERROR——两者冲突。
 * 因此范围校验<b>只在服务层</b>做（读 {@code medi.wallet.min/max-recharge} 配置返回 4006），
 * 这里只用 {@code @NotNull} 兜住「完全没传」的情形。
 */
@Data
public class RechargeRequest {

    @NotNull(message = "充值金额不能为空")
    private BigDecimal amount;

    /** 预设档位码，如 C50/C100/C500，仅用于统计 */
    @Size(max = 16, message = "预设档位码长度不能超过 16")
    private String presetCode;
}
