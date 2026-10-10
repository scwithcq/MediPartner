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
    private Integer orderStatus;
    private String geoHash;
    private String serviceType;
    private String symptomDesc;
    private String city;
    private String hospitalPref;
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
