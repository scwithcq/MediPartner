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
    private BigDecimal balance;
    private BigDecimal frozen;
    private Integer version;
}
