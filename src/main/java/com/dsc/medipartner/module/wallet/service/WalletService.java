package com.dsc.medipartner.module.wallet.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.module.wallet.domain.dto.TransactionQuery;
import com.dsc.medipartner.module.wallet.domain.entity.WalletAccount;
import com.dsc.medipartner.module.wallet.domain.entity.WalletTransaction;
import com.dsc.medipartner.module.wallet.domain.vo.TransactionVO;
import com.dsc.medipartner.module.wallet.domain.vo.WalletVO;

import java.math.BigDecimal;

public interface WalletService {

    WalletAccount getAccount(Long userId);

    /** §3.6 钱包账户（VO 化：不暴露 id/version，补 availableBalance 与累计收支） */
    WalletVO getAccountVO(Long userId);

    /** §3.7 资金流水：支持 type/orderNo 筛选、排序白名单与 updatedSince 增量轮询 */
    IPage<WalletTransaction> pageTransactions(Long userId, TransactionQuery query);

    /** §3.7 资金流水（VO 化：补 typeName/direction） */
    IPage<TransactionVO> pageTransactionVOs(Long userId, TransactionQuery query);

    String freeze(Long userId, String orderNo, BigDecimal amount);

    void unfreeze(Long userId, String orderNo, BigDecimal amount);

    void settle(String orderNo, Long userId, Long workerId, BigDecimal amount);

    void refund(Long userId, String orderNo, BigDecimal amount);
}
