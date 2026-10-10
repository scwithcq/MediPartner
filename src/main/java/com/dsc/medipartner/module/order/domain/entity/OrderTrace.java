package com.dsc.medipartner.module.order.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsc.medipartner.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 订单服务留痕（契约 V10 order_trace 表）。
 * 一单多条，seq 单内自增；type 区分过程留痕 TRACE 与结束凭证 VOUCHER。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("order_trace")
public class OrderTrace extends BaseEntity {

    private String orderNo;
    private Long workerId;
    /** TRACE / VOUCHER */
    private String type;
    /** 单内序号，从 1 开始 */
    private Integer seq;
    private String imageUrl;
    private String gpsLat;
    private String gpsLng;
    private String address;
    private LocalDateTime watermarkTime;
    /** 服务节点码值：ARRIVED/REGISTER/WAITING/VISIT/PHARMACY/OTHER */
    private String node;
    private String remark;
}
