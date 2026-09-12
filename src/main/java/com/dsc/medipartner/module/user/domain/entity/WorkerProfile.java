package com.dsc.medipartner.module.user.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsc.medipartner.common.entity.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;

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
}
