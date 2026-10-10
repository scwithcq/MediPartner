package com.dsc.medipartner.module.order.domain.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单详情（契约 §5.4）= {@link OrderCardVO} 全部字段 + 下列扩展。
 *
 * <p>聚合 VO：详情页一次请求渲染整页，不返回数据库实体（附录 H-4）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderDetailVO extends OrderCardVO {

    /** 订单主键（客服沟通用，序列化为字符串） */
    private Long orderId;
    /** 完整症状描述（不截断） */
    private String symptomDesc;
    /** <b>脱敏</b>联系电话 */
    private String contactPhone;
    private String geoHash;
    /** 计价明细，阶段 1.5 起有值 */
    private PriceDetailVO priceDetail;
    /** 关联需求工单 */
    private Long demandId;
    private String demandNo;
    /** 取消原因（orderStatus=60 时有值） */
    private String cancelReason;
    /** 已退款金额 */
    private BigDecimal refundAmount;
    private LocalDateTime acceptedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime confirmedAt;
    /** 最近 5 条状态流转（完整列表走 §5.5） */
    private java.util.List<OrderTimelineNodeVO> timeline;
    /** AI 报告状态摘要 */
    private ReportBriefVO report;
}
