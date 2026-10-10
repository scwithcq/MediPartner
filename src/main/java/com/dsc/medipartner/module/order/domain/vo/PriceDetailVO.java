package com.dsc.medipartner.module.order.domain.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 计价明细（契约 §5.4 的 {@code priceDetail}、§5.1 的 {@code PricePreviewVO} 同构）。
 * 唯一来源是服务端 {@code PriceService}，前端只展示不计算（§1.4 铁律 2）。
 */
@Data
public class PriceDetailVO {

    private String serviceType;
    private String serviceTypeName;
    /** 计费时长（小时） */
    private BigDecimal hours;
    private BigDecimal basePrice;
    /** 超出起步时长的加时小时数（0.5h 粒度向上取整） */
    private BigDecimal extraHours;
    private BigDecimal extraHourPrice;
    /** 应付总额 = basePrice + extraHours × extraHourPrice */
    private BigDecimal amount;
    private Long priceRuleId;
    private String priceVersion;
    private String remark;
}
