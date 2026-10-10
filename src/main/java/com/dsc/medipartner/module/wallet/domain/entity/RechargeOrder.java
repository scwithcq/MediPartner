package com.dsc.medipartner.module.wallet.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsc.medipartner.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充值单（契约 §3.8/§3.9；表由 V17 建）。
 *
 * <p>状态机：{@code PENDING} →（回调成功）{@code SUCCESS} /（回调失败）{@code FAILED}
 * /（超时，阶段 5 定时任务）{@code EXPIRED}。状态迁移天然幂等，重复回调不再入账。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("recharge_order")
public class RechargeOrder extends BaseEntity {

    private String rechargeNo;
    private Long userId;
    private BigDecimal amount;
    /** 预设档位码（C50/C100/C500），仅统计用 */
    private String presetCode;
    /** PENDING / SUCCESS / FAILED / EXPIRED */
    private String status;
    /** 成功后的 RECHARGE 流水号 */
    private String txnNo;
    private LocalDateTime expireAt;
    private LocalDateTime paidAt;
    private String remark;
}
