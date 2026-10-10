package com.dsc.medipartner.module.order.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsc.medipartner.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("service_order")
public class ServiceOrder extends BaseEntity {

    private String orderNo;
    private Long userId;
    private Long workerId;
    /** 关联需求工单 ID（demand_order.id），下单携带则回写工单 OPEN->ORDERED */
    private Long demandId;
    private BigDecimal amount;
    /** 下单时锁定的定价版本号（V13，来自 service_price_rule.price_version） */
    private String priceVersion;
    /** 命中的定价规则 ID（V13，service_price_rule.id） */
    private Long priceRuleId;
    private Integer orderStatus;
    private String geoHash;
    private String serviceType;
    /** 预计服务时长（小时，V13，用户下单填写，参与计价） */
    private BigDecimal serviceHours;
    private String symptomDesc;
    private String city;
    /** 联系电话（V13，不传则用账号绑定手机号） */
    private String contactPhone;
    private String hospitalPref;
    /** 下单补充说明（V13，契约 §5.2 remark） */
    private String remark;
    private LocalDateTime serviceTime;
    private Integer version;
    private LocalDateTime acceptedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime confirmedAt;
    /** 陪诊师结束服务时提交的服务小结（AI 报告素材，V9） */
    private String serviceSummary;
    /** 实际服务时长（小时，一位小数，V9） */
    private BigDecimal actualHours;
}
