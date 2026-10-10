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

    /**
     * §3.9 充值入账：{@code balance += amount} 并写 {@code RECHARGE} 流水（{@code biz_no = rechargeNo}）。
     * 由充值回调在同一事务内调用。
     *
     * @return 流水号 txnNo
     */
    String recharge(Long userId, String rechargeNo, BigDecimal amount);

    /**
     * §7.18 提现出账：{@code balance -= amount}、{@code withdrawing += amount}，
     * 并写 {@code WITHDRAW} 流水（{@code biz_no = withdrawNo}）。申请时调用。
     */
    void withdrawApply(Long userId, String withdrawNo, BigDecimal amount);

    /**
     * §9.21 提现驳回回退：{@code balance += amount}、{@code withdrawing -= amount}，
     * 写 {@code REFUND} 流水（{@code biz_no = withdrawNo}，与 WITHDRAW 靠 type 区分）。
     */
    void withdrawReject(Long userId, String withdrawNo, BigDecimal amount);
}
