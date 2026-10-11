package com.dsc.medipartner.module.order.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单列表卡片（契约 §5.3，求助者视角）。
 *
 * <p>阶段 1.5 由裸 {@code ServiceOrder} 实体改造而来（附录 H-4）：不再暴露 {@code id} /
 * {@code userId} / {@code workerId} / {@code version}，并补 {@code orderStatusName}、
 * {@code worker} 摘要、{@code actions}、{@code symptomBrief}。
 *
 * <p>{@code orderStatus} 按附录 B.1 下发<b>枚举名字符串</b>（如 {@code "ACCEPTED"}），
 * 与 {@code orderStatusName} 中文名配套，前端不硬编码中文。
 */
@Data
public class OrderCardVO {

    private String orderNo;
    /** 状态枚举名：WAITING_ACCEPT/ACCEPTED/IN_SERVICE/PENDING_CONFIRM/COMPLETED/CANCELLED/ABNORMAL */
    private String orderStatus;
    /** 状态中文名，如「已接单」 */
    private String orderStatusName;
    private String serviceType;
    private String serviceTypeName;
    /** 症状摘要，后端截断至 50 字并加省略号 */
    private String symptomBrief;
    private String city;
    private String hospitalPref;
    private LocalDateTime serviceTime;
    private BigDecimal amount;
    /** 接单陪诊师摘要；未接单时该键缺失 */
    private WorkerBriefVO worker;
    /** 当前可执行操作码（附录 §5.3 动作字典），由 ActionResolver 计算 */
    private List<String> actions;
    private LocalDateTime createdAt;
    /** 最近变更时间（增量轮询依据，§1.9） */
    private LocalDateTime updatedAt;

    /**
     * 阶段 5（§7.5）：距陪诊师的距离（公里，一位小数）。
     *
     * <p><b>未传坐标时该键缺失</b>（保持 {@code null} 由 Jackson 的
     * {@code NON_NULL} 策略自动省略），而不是下发 0——0 会被误读为「就在楼下」。
     */
    private java.math.BigDecimal distanceKm;

    /**
     * 阶段 5（§7.5）：距超时自动取消的剩余秒数，供前端做倒计时。
     *
     * <p>仅接单大厅下发；其它列表无此语义。
     */
    private Long countdownSeconds;
}
