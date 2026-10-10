package com.dsc.medipartner.module.order.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 服务费试算（契约 §5.1）。
 *
 * <p>计价结果唯一来源是服务端 {@code PriceService}，试算仅用于展示；下单时后端会重算，
 * 前端拿本响应的 {@code priceVersion} 回传给 §5.2 做一致性校验。
 */
@Data
public class PricePreviewVO {

    private String serviceType;
    private String serviceTypeName;
    /** 命中的城市码；回落全国默认规则时为 "DEFAULT" */
    private String city;
    /** 计费时长（小时） */
    private BigDecimal hours;
    private BigDecimal basePrice;
    /** 超出起步时长的加时小时数（0.5h 粒度向上取整） */
    private BigDecimal extraHours;
    private BigDecimal extraHourPrice;
    /** 应付总额 = basePrice + extraHours × extraHourPrice */
    private BigDecimal amount;
    private Long priceRuleId;
    /** 规则版本号，回传给 §5.2 校验 */
    private String priceVersion;
    /** 本报价有效截止时间（默认 30 分钟） */
    private LocalDateTime validUntil;
    private String remark;
}
