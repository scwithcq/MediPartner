package com.dsc.medipartner.module.user.domain.row;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * worker_profile JOIN sys_user 的查询投影（§6.1/§6.2 共用），由 WorkerBrowseService 组装成 VO。
 * idCardVerified 在 SQL 里由 id_card_no 非空推导，证件号本身不出库。
 */
@Data
public class WorkerBrowseRow {

    private Long userId;
    private String nickname;
    private String avatar;
    private String realName;
    private String healthCertUrl;
    private String auditStatus;
    private LocalDateTime auditTime;
    private Integer creditScore;
    private String city;
    private String serviceTypes;
    private String intro;
    private Integer goodRate;
    private Integer reviewCount;
    private Integer serviceCount;
    private BigDecimal avgStar;
    private Boolean idCardVerified;
    /** worker_profile.status：1 可接单、0 停用；browseDetail 用于 §6.2 的 2003 闸门 */
    private Integer status;
    private LocalDateTime updatedAt;

    /**
     * 阶段 5（§7.4）：陪诊师最新上报的经纬度，<b>仅用于服务层内存精算距离</b>。
     *
     * <p>刻意不映射到任何对外 VO——坐标属敏感位置数据，
     * 前端只需要 {@code distanceKm} 这个结果，不需要原始坐标。
     */
    private BigDecimal gpsLat;
    private BigDecimal gpsLng;
}
