package com.dsc.medipartner.module.user.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsc.medipartner.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("credit_change_log")
public class CreditChangeLog extends BaseEntity {

    private Long workerUserId;
    private String changeType;
    private Integer changeScore;
    private Integer beforeScore;
    private Integer afterScore;
    private String orderNo;
    private Long reviewId;
    private String reason;
}
