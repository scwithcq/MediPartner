package com.dsc.medipartner.module.ticket.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsc.medipartner.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 申诉工单（契约 §5.14/§5.15；表由 V17 建）。
 *
 * <p>「一单一诉」由 {@code uk_order_type (order_no, appeal_type)} 在数据库级兜底（§5.14 的 5002）。
 * {@code autoConfirmPaused} 记录「该单的 48h 自动确认已被暂停」，供阶段 5 定时任务排除
 * （本阶段只落标记，定时逻辑属阶段 5）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("appeal_ticket")
public class AppealTicket extends BaseEntity {

    private String appealNo;
    private String orderNo;
    private Long applicantId;
    /** USER / WORKER / SYSTEM */
    private String applicantRole;
    /** 申诉类型（附录 B.7 AppealType） */
    private String appealType;
    private String reason;
    /** 证据图片相对路径，逗号分隔，最多 6 张 */
    private String evidenceUrls;
    private BigDecimal expectRefundAmount;
    private String contactPhone;
    /** 争议订单金额快照（便于列表展示） */
    private BigDecimal amount;
    /** PENDING / PROCESSING / CLOSED */
    private String status;
    /** 是否已暂停该单 48h 自动确认：1 是 0 否 */
    private Integer autoConfirmPaused;
    private String arbitrateResult;
    private BigDecimal refundAmount;
    private String conclusion;
    private Long adminId;
    private String adminRemark;
    private LocalDateTime handleTime;
    private Integer version;
}
