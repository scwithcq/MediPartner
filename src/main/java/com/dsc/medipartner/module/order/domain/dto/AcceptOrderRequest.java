package com.dsc.medipartner.module.order.domain.dto;

import lombok.Data;

/**
 * 抢单请求（契约 §7.6，阶段 5 新增，字段均可选）。
 *
 * <p>不加 Bean Validation：坐标格式非法时不应拒绝请求，而应「当作没传」放行——
 * 契约 §7.4 备注 3 明确「定位失败不阻塞主流程」。若用 {@code @Pattern} 拦下，
 * 一个定位异常的客户端会彻底无法接单，而定位本就不该是接单的必要条件。
 */
@Data
public class AcceptOrderRequest {

    /** 抢单时的实时定位纬度，6 位小数；用于距离校验与留痕 */
    private String gpsLat;

    /** 抢单时的实时定位经度 */
    private String gpsLng;

    /** 客户端点击时间 {@code yyyy-MM-dd HH:mm:ss}，排查抢单争议用 */
    private String clientTs;
}
