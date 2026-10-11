package com.dsc.medipartner.module.order.domain.dto;

import com.dsc.medipartner.common.result.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 订单列表查询条件（契约 §5.3 求助者侧、§7.7 陪诊师侧）。
 *
 * <p>{@code sortBy}/{@code order}/{@code updatedSince} 继承自 {@link PageQuery}（§1.3），
 * 排序字段由 {@code SortWhitelist.Spec.ORDER_LIST} 校验。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderQuery extends PageQuery {

    /** 单状态筛选，取值 10/20/30/40/50/60/70（§5.3） */
    private Integer orderStatus;

    /**
     * 状态分组（§5.3）：{@code PROCESSING}(10,20,30,40) / {@code FINISHED}(50) / {@code CLOSED}(60,70)。
     * 与 {@code orderStatus} 互斥，同时传时以 {@code orderStatus} 为准。
     */
    private String statusGroup;

    /** 按订单号/城市/症状关键词模糊搜索（§5.3 阶段 1.5） */
    private String keyword;

    /**
     * 阶段 5（§7.5）：陪诊师当前纬度，用于就近排序与 distanceKm。
     *
     * <p>可空。为空时「按距离排序」退化为默认排序，且不下发 {@code distanceKm}
     * （契约 §7.5：未传坐标时该键缺失）。
     */
    private String gpsLat;

    /** 阶段 5（§7.5）：陪诊师当前经度 */
    private String gpsLng;

    /** 阶段 5（§7.5）：最低客单价过滤，字符串承载以对齐契约的金额口径 */
    private String minAmount;
}
