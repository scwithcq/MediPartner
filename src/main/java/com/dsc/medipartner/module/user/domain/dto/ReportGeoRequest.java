package com.dsc.medipartner.module.user.domain.dto;

import lombok.Data;

/**
 * §7.4 上报定位请求。
 *
 * <p>经纬度用 String 承载（契约要求「字符串保留 6 位小数」），在 Service 层解析为
 * {@link java.math.BigDecimal}。刻意不用 {@code double}：二进制浮点在边界值上会出现
 * 「同一坐标两次上报，一次通过一次不通过」的不可复现问题。
 *
 * <p>不加 Bean Validation：坐标非法时应返回 {@code 1001} 并带上明确说明，
 * 而注解会被 {@code GlobalExceptionHandler} 压成通用 1001 文案，丢失可诊断性。
 */
@Data
public class ReportGeoRequest {

    /** 纬度，6 位小数，如 "23.106590" */
    private String gpsLat;

    /** 经度，6 位小数，如 "113.324553" */
    private String gpsLng;
}
