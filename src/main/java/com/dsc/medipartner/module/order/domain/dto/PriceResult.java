package com.dsc.medipartner.module.order.domain.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 服务端算价结果（契约 §5.1 PricePreviewVO 与 §5.2 下单共用的计价产物）。
 * 由 PriceService.calc(...) 产出，是订单金额的唯一来源（附录 H-1）。
 */
@Data
public class PriceResult {

    private String serviceType;
    private String serviceTypeName;
    /** 命中的城市码；回落全国默认规则时为 "DEFAULT" */
    private String city;
    /** 计费时长（小时） */
    private BigDecimal hours;
    private BigDecimal basePrice;
    /** 超出起步时长的加时小时数（已按 0.5h 粒度向上取整） */
    private BigDecimal extraHours;
    private BigDecimal extraHourPrice;
    /** 应付总额 = basePrice + extraHours × extraHourPrice */
    private BigDecimal amount;
    private Long priceRuleId;
    private String priceVersion;
    private String remark;
}
