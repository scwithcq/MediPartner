package com.dsc.medipartner.module.order.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单流转日志（契约 §5.5、附录 B.10；表由 V15 建）。
 *
 * <p>由状态机 listener 统一落库，业务代码不手写日志——这是「保证不漏记」的实现手段。
 */
@Data
@TableName("order_status_log")
public class OrderStatusLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String orderNo;
    /** 变更前状态码；下单初始节点为 null */
    private Integer fromState;
    private Integer toState;
    /** 触发事件（附录 B.2 OrderEvent） */
    private String event;
    /** USER / WORKER / ADMIN / SYSTEM（附录 B.10） */
    private String operatorType;
    private Long operatorId;
    private String operatorName;
    /** 展示标题，如「陪诊师已接单」 */
    private String title;
    private String remark;
    private String gpsLat;
    private String gpsLng;
    /** 变更前后关键字段快照 JSON（管理端 §9.15 仲裁用） */
    private String snapshot;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
