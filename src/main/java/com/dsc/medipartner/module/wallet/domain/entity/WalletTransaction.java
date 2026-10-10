package com.dsc.medipartner.module.wallet.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("wallet_transaction")
public class WalletTransaction {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String txnNo;
    /**
     * 业务幂等键（V12，契约附录 H-2）：取值如 orderNo / withdrawNo / rechargeNo。
     * 与 {@code type} 组成唯一键 {@code uk_biz_no_type}，是资金重复入账的最后防线。
     */
    private String bizNo;
    private Long userId;
    private String orderNo;
    private String type;
    private BigDecimal amount;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private BigDecimal frozenBefore;
    private BigDecimal frozenAfter;
    private String relatedTxnNo;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
