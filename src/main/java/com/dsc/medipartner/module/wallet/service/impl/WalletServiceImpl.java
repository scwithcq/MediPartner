package com.dsc.medipartner.module.wallet.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.util.OrderNoGenerator;
import com.dsc.medipartner.module.wallet.domain.entity.WalletAccount;
import com.dsc.medipartner.module.wallet.domain.entity.WalletTransaction;
import com.dsc.medipartner.module.wallet.domain.enums.TxnTypeEnum;
import com.dsc.medipartner.module.wallet.mapper.WalletAccountMapper;
import com.dsc.medipartner.module.wallet.mapper.WalletTransactionMapper;
import com.dsc.medipartner.module.wallet.service.WalletService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class WalletServiceImpl implements WalletService {

    private static final int MAX_RETRY = 3;

    private final WalletAccountMapper accountMapper;
    private final WalletTransactionMapper transactionMapper;

    public WalletServiceImpl(WalletAccountMapper accountMapper, WalletTransactionMapper transactionMapper) {
        this.accountMapper = accountMapper;
        this.transactionMapper = transactionMapper;
    }

    @Override
    public WalletAccount getAccount(Long userId) {
        return getOrCreate(userId);
    }

    @Override
    public IPage<WalletTransaction> pageTransactions(Long userId, long pageNum, long pageSize) {
        Page<WalletTransaction> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<WalletTransaction> query = new LambdaQueryWrapper<WalletTransaction>()
                .eq(WalletTransaction::getUserId, userId)
                .orderByDesc(WalletTransaction::getCreatedAt);
        return transactionMapper.selectPage(page, query);
    }

    @Override
    @Transactional
    public String freeze(Long userId, String orderNo, BigDecimal amount) {
        checkAmount(amount);
        for (int i = 0; i < MAX_RETRY; i++) {
            WalletAccount account = getOrCreate(userId);
            if (account.getBalance().subtract(account.getFrozen()).compareTo(amount) < 0) {
                throw new BizException(ErrorCode.WALLET_BALANCE_NOT_ENOUGH);
            }
            int updated = accountMapper.update(null, new LambdaUpdateWrapper<WalletAccount>()
                    .eq(WalletAccount::getUserId, userId)
                    .eq(WalletAccount::getVersion, account.getVersion())
                    .set(WalletAccount::getFrozen, account.getFrozen().add(amount))
                    .set(WalletAccount::getVersion, account.getVersion() + 1));
            if (updated == 1) {
                return record(userId, orderNo, TxnTypeEnum.FREEZE, amount,
                        account.getBalance(), account.getBalance(),
                        account.getFrozen(), account.getFrozen().add(amount), "下单冻结资金");
            }
        }
        throw new BizException(ErrorCode.WALLET_UPDATE_CONFLICT);
    }

    @Override
    @Transactional
    public void unfreeze(Long userId, String orderNo, BigDecimal amount) {
        checkAmount(amount);
        for (int i = 0; i < MAX_RETRY; i++) {
            WalletAccount account = getOrCreate(userId);
            if (account.getFrozen().compareTo(amount) < 0) {
                throw new BizException(ErrorCode.WALLET_BALANCE_NOT_ENOUGH);
            }
            int updated = accountMapper.update(null, new LambdaUpdateWrapper<WalletAccount>()
                    .eq(WalletAccount::getUserId, userId)
                    .eq(WalletAccount::getVersion, account.getVersion())
                    .set(WalletAccount::getFrozen, account.getFrozen().subtract(amount))
                    .set(WalletAccount::getVersion, account.getVersion() + 1));
            if (updated == 1) {
                record(userId, orderNo, TxnTypeEnum.UNFREEZE, amount,
                        account.getBalance(), account.getBalance(),
                        account.getFrozen(), account.getFrozen().subtract(amount), "取消订单解冻");
                return;
            }
        }
        throw new BizException(ErrorCode.WALLET_UPDATE_CONFLICT);
    }

    @Override
    @Transactional
    public void settle(String orderNo, Long userId, Long workerId, BigDecimal amount) {
        checkAmount(amount);

        WalletAccount user = getOrCreate(userId);
        int userUpdated = accountMapper.update(null, new LambdaUpdateWrapper<WalletAccount>()
                .eq(WalletAccount::getUserId, userId)
                .eq(WalletAccount::getVersion, user.getVersion())
                .set(WalletAccount::getFrozen, user.getFrozen().subtract(amount))
                .set(WalletAccount::getBalance, user.getBalance().subtract(amount))
                .set(WalletAccount::getVersion, user.getVersion() + 1));
        if (userUpdated != 1) {
            throw new BizException(ErrorCode.WALLET_UPDATE_CONFLICT);
        }
        record(userId, orderNo, TxnTypeEnum.PAY, amount,
                user.getBalance(), user.getBalance().subtract(amount),
                user.getFrozen(), user.getFrozen().subtract(amount), "订单结算扣款");

        WalletAccount worker = getOrCreate(workerId);
        int workerUpdated = accountMapper.update(null, new LambdaUpdateWrapper<WalletAccount>()
                .eq(WalletAccount::getUserId, workerId)
                .eq(WalletAccount::getVersion, worker.getVersion())
                .set(WalletAccount::getBalance, worker.getBalance().add(amount))
                .set(WalletAccount::getVersion, worker.getVersion() + 1));
        if (workerUpdated != 1) {
            throw new BizException(ErrorCode.WALLET_UPDATE_CONFLICT);
        }
        record(workerId, orderNo, TxnTypeEnum.INCOME, amount,
                worker.getBalance(), worker.getBalance().add(amount),
                worker.getFrozen(), worker.getFrozen(), "订单收入");
    }

    @Override
    @Transactional
    public void refund(Long userId, String orderNo, BigDecimal amount) {
        checkAmount(amount);
        for (int i = 0; i < MAX_RETRY; i++) {
            WalletAccount account = getOrCreate(userId);
            int updated = accountMapper.update(null, new LambdaUpdateWrapper<WalletAccount>()
                    .eq(WalletAccount::getUserId, userId)
                    .eq(WalletAccount::getVersion, account.getVersion())
                    .set(WalletAccount::getBalance, account.getBalance().add(amount))
                    .set(WalletAccount::getVersion, account.getVersion() + 1));
            if (updated == 1) {
                record(userId, orderNo, TxnTypeEnum.REFUND, amount,
                        account.getBalance(), account.getBalance().add(amount),
                        account.getFrozen(), account.getFrozen(), "退款");
                return;
            }
        }
        throw new BizException(ErrorCode.WALLET_UPDATE_CONFLICT);
    }

    private void checkAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "金额必须大于0");
        }
    }

    private WalletAccount getOrCreate(Long userId) {
        WalletAccount account = accountMapper.selectOne(new LambdaQueryWrapper<WalletAccount>()
                .eq(WalletAccount::getUserId, userId));
        if (account != null) {
            return account;
        }
        WalletAccount created = new WalletAccount();
        created.setUserId(userId);
        created.setBalance(BigDecimal.ZERO);
        created.setFrozen(BigDecimal.ZERO);
        created.setVersion(0);
        try {
            accountMapper.insert(created);
            return created;
        } catch (DuplicateKeyException e) {
            return accountMapper.selectOne(new LambdaQueryWrapper<WalletAccount>()
                    .eq(WalletAccount::getUserId, userId));
        }
    }

    private String record(Long userId, String orderNo, TxnTypeEnum type, BigDecimal amount,
                          BigDecimal balanceBefore, BigDecimal balanceAfter,
                          BigDecimal frozenBefore, BigDecimal frozenAfter, String remark) {
        WalletTransaction txn = new WalletTransaction();
        txn.setTxnNo(OrderNoGenerator.next("TX"));
        txn.setUserId(userId);
        txn.setOrderNo(orderNo);
        txn.setType(type.name());
        txn.setAmount(amount);
        txn.setBalanceBefore(balanceBefore);
        txn.setBalanceAfter(balanceAfter);
        txn.setFrozenBefore(frozenBefore);
        txn.setFrozenAfter(frozenAfter);
        txn.setRemark(remark);
        transactionMapper.insert(txn);
        return txn.getTxnNo();
    }
}
