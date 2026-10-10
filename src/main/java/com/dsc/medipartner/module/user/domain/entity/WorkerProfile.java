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
    private Long auditBy;
    private LocalDateTime auditTime;
    private String auditRemark;

    /** 常驻城市（V10） */
    private String city;
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
