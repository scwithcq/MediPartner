package com.dsc.medipartner.module.order.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsc.medipartner.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 服务定价规则（契约 §5.1、附录 B.6；表由 V11 建）。
 * 唯一计价入口 PriceService 的数据来源；city_code + service_type 唯一。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("service_price_rule")
public class ServicePriceRule extends BaseEntity {

    /** 国标 6 位行政区划码；DEFAULT 表示全国默认规则 */
    private String cityCode;
    private String cityName;
    /** 服务类型码值（ServiceType 枚举名） */
    private String serviceType;
    private BigDecimal basePrice;
    /** 起步价包含的时长（小时） */
    private BigDecimal baseHours;
    /** 加时单价（每小时）；一口价服务为 0 */
    private BigDecimal extraHourPrice;
    private BigDecimal maxHours;
    private BigDecimal minHours;
    private BigDecimal platformRate;
    /** 定价版本号，调价时递增；下单带旧版本返回 3010 */
    private String priceVersion;
    private Integer enabled;
    private String remark;
    private LocalDateTime effectiveAt;
}
