package com.dsc.medipartner.module.order.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单流转时间轴节点（契约 §5.5、§7.9）。
 * 数据来源 {@code order_status_log}，由状态机 listener 统一落库，业务代码不手写日志。
 */
@Data
public class OrderTimelineNodeVO {

    /** 该节点到达的状态码（10~70） */
    private Integer status;
    private String statusName;
    /** 触发事件（附录 B.2 OrderEvent） */
    private String event;
    /** 展示标题，如「陪诊师已接单」 */
    private String title;
    /** 补充说明（取消原因、异常说明等） */
    private String remark;
    /** USER / WORKER / SYSTEM / ADMIN */
    private String operatorType;
    /** 操作人展示名（脱敏；SYSTEM 时为「系统」） */
    private String operatorName;
    private LocalDateTime occurredAt;
}
