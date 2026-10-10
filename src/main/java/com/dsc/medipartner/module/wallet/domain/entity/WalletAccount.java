package com.dsc.medipartner.module.wallet.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsc.medipartner.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wallet_account")
public class WalletAccount extends BaseEntity {

    private Long userId;
    /** 总余额；可用余额 = balance - frozen（附录 B.3） */
    private BigDecimal balance;
    private BigDecimal frozen;
    /** 提现处理中金额（V14，§7.18） */
    private BigDecimal withdrawing;
    /** 累计收入（V14，§3.6） */
    private BigDecimal totalIncome;
    /** 累计充值（V14） */
    private BigDecimal totalRecharge;
    /** 累计支出（V14，§3.6） */
    private BigDecimal totalExpense;
    private Integer version;
}
