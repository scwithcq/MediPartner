package com.dsc.medipartner.module.user.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsc.medipartner.common.entity.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("worker_profile")
public class WorkerProfile extends BaseEntity {

    private Long userId;
    private String realName;

    @JsonIgnore
    private String idCardNo;

    private String healthCertUrl;
    private String auditStatus;
    private Integer creditScore;
    private String geoHash;

    /**
     * 最新上报纬度（V18，§7.4）。
     *
     * <p>与 {@link #geoHash} 的分工：geo_hash 用于**前缀粗筛**（前 6 位约 1.2km 精度），
     * 原始经纬度用于**内存精算距离**（契约 §7.4 备注 1）。仅有 geo_hash 无法算出精确距离。
     * 用 {@link BigDecimal} 而非 double，避免边界值上出现不可复现的距离判定。
     */
    private BigDecimal gpsLat;
    /** 最新上报经度（V18，§7.4） */
    private BigDecimal gpsLng;
    /** 定位最近上报时间（V18），用于判断定位是否已过期 */
    private LocalDateTime geoUpdatedAt;

    private Long auditBy;
    private LocalDateTime auditTime;
    private String auditRemark;

    /** 常驻城市（V10） */
    private String city;
    /** 常驻城市国标 6 位码（V14，H-12：筛选与就近排序口径） */
    private String cityCode;
    /** 可接单服务类型码值，逗号分隔（V10） */
    private String serviceTypes;
    /** 个人简介（V10） */
    private String intro;
    /** 好评率百分比整数，star>=4 计好评；无评价为 100（V10） */
    private Integer goodRate;
    /** 评价总数（V10） */
    private Integer reviewCount;
    /** 已完成订单数（V10） */
    private Integer serviceCount;
    /** 平均星级一位小数，无评价为 5.0（V10） */
    private BigDecimal avgStar;
    /** 上架状态：1 上架 0 下架（V10） */
    private Integer status;
}
