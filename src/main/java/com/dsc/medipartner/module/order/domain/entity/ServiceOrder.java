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
}
