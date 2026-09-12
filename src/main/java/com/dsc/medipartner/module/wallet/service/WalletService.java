package com.dsc.medipartner.module.wallet.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.module.wallet.domain.entity.WalletAccount;
import com.dsc.medipartner.module.wallet.domain.entity.WalletTransaction;

import java.math.BigDecimal;

public interface WalletService {

    WalletAccount getAccount(Long userId);

    IPage<WalletTransaction> pageTransactions(Long userId, long pageNum, long pageSize);

    String freeze(Long userId, String orderNo, BigDecimal amount);

    void unfreeze(Long userId, String orderNo, BigDecimal amount);

    void settle(String orderNo, Long userId, Long workerId, BigDecimal amount);

    void refund(Long userId, String orderNo, BigDecimal amount);
}
