package com.dsc.medipartner.module.order.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * 订单流转时间轴（契约 §5.5、§7.9，两端结构完全一致）。
 */
@Data
public class OrderTimelineVO {

    private String orderNo;
    /** 当前状态码（10~70） */
    private Integer currentStatus;
    /** 按时间<b>升序</b>返回（前端可自行倒序渲染） */
    private List<OrderTimelineNodeVO> nodes;
}
