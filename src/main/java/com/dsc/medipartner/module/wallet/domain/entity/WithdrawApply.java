package com.dsc.medipartner.module.wallet.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsc.medipartner.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 提现申请（契约 §7.18/§7.19；表由 V17 建）。
 *
 * <p>资金语义：<b>申请时即扣减 {@code wallet_account.balance} 并累加 {@code withdrawing}</b>；
 * 管理端审核通过（§9.20）只清零 {@code withdrawing} 不再产生流水（避免重复记账），
 * 驳回（§9.21）则把余额加回并写一条 {@code REFUND} 流水。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("withdraw_apply")
public class WithdrawApply extends BaseEntity {

    private String withdrawNo;
    private Long workerId;
    private BigDecimal amount;
    /** 手续费，本项目恒为 0.00（字段保留以便将来接真实通道不改契约） */
    private BigDecimal fee;
    /** 实际到账 = amount - fee */
    private BigDecimal actualAmount;
    /** WECHAT（唯一实现）/ BANK（预留） */
    private String channel;
    /** PENDING / SUCCESS / REJECT */
    private String status;
    private String remark;
    private String auditRemark;
    private Long auditAdminId;
    private LocalDateTime auditTime;
    private LocalDateTime finishedAt;
    private Integer version;
}
