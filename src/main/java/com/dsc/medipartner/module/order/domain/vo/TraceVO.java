package com.dsc.medipartner.module.order.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服务留痕（契约 §5.9 / §7.13 共用结构，§7.13 额外含 node）。
 * traceId 为字符串（Long 精度保护），imageUrl 是 /files/ 相对路径。
 */
@Data
public class TraceVO {

    private String traceId;
    /** TRACE 过程留痕 / VOUCHER 结束凭证 */
    private String type;
    private String imageUrl;
    private String gpsLat;
    private String gpsLng;
    private String address;
    private LocalDateTime watermarkTime;
    /** 服务节点码值：ARRIVED/REGISTER/WAITING/VISIT/PHARMACY/OTHER */
    private String node;
    private String remark;
    private LocalDateTime createdAt;
}
