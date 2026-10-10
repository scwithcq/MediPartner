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
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
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
                return record(userId, orderNo, orderNo, TxnTypeEnum.FREEZE, amount,
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
                record(userId, orderNo, orderNo, TxnTypeEnum.UNFREEZE, amount,
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

        // 契约附录 H-3：重复结算守卫。已存在该订单的 PAY 流水即视为已结算，直接返回不二次扣款。
        // 判据用 PAY（用户侧支出流水）而非任意流水，因为同一 orderNo 下 FREEZE/PAY/INCOME 合法共存。
        if (existsTxn(orderNo, TxnTypeEnum.PAY)) {
            log.info("[wallet] 订单已结算，跳过重复结算 orderNo={}", orderNo);
            return;
        }

        // 用户侧：解冻并扣款（H-3 整改：补 MAX_RETRY 乐观锁重试，与 freeze/unfreeze/refund 对齐）
        settleUserSide(orderNo, userId, amount);
        // 陪诊师侧：入账
        settleWorkerSide(orderNo, workerId, amount);
    }

    /** 用户侧解冻扣款，乐观锁冲突时按 MAX_RETRY 重试（每次重试重新读取最新 version） */
    private void settleUserSide(String orderNo, Long userId, BigDecimal amount) {
        for (int i = 0; i < MAX_RETRY; i++) {
            WalletAccount user = getOrCreate(userId);
            if (user.getFrozen().compareTo(amount) < 0) {
                // 冻结额不足以结算，说明资金状态异常，拒绝扣款以免出现负冻结
                throw new BizException(ErrorCode.WALLET_UPDATE_CONFLICT, "订单冻结金额不足，无法结算");
            }
            int userUpdated = accountMapper.update(null, new LambdaUpdateWrapper<WalletAccount>()
                    .eq(WalletAccount::getUserId, userId)
                    .eq(WalletAccount::getVersion, user.getVersion())
                    .set(WalletAccount::getFrozen, user.getFrozen().subtract(amount))
                    .set(WalletAccount::getBalance, user.getBalance().subtract(amount))
                    .set(WalletAccount::getVersion, user.getVersion() + 1));
            if (userUpdated == 1) {
                record(userId, orderNo, orderNo, TxnTypeEnum.PAY, amount,
                        user.getBalance(), user.getBalance().subtract(amount),
                        user.getFrozen(), user.getFrozen().subtract(amount), "订单结算扣款");
                return;
            }
        }
        throw new BizException(ErrorCode.WALLET_UPDATE_CONFLICT);
    }

    /** 陪诊师侧入账，乐观锁冲突时按 MAX_RETRY 重试 */
    private void settleWorkerSide(String orderNo, Long workerId, BigDecimal amount) {
        for (int i = 0; i < MAX_RETRY; i++) {
            WalletAccount worker = getOrCreate(workerId);
            int workerUpdated = accountMapper.update(null, new LambdaUpdateWrapper<WalletAccount>()
                    .eq(WalletAccount::getUserId, workerId)
                    .eq(WalletAccount::getVersion, worker.getVersion())
                    .set(WalletAccount::getBalance, worker.getBalance().add(amount))
                    .set(WalletAccount::getVersion, worker.getVersion() + 1));
            if (workerUpdated == 1) {
                record(workerId, orderNo, orderNo, TxnTypeEnum.INCOME, amount,
                        worker.getBalance(), worker.getBalance().add(amount),
                        worker.getFrozen(), worker.getFrozen(), "订单收入");
                return;
            }
        }
        throw new BizException(ErrorCode.WALLET_UPDATE_CONFLICT);
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
                record(userId, orderNo, orderNo, TxnTypeEnum.REFUND, amount,
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

    private String record(Long userId, String orderNo, String bizNo, TxnTypeEnum type, BigDecimal amount,
                          BigDecimal balanceBefore, BigDecimal balanceAfter,
                          BigDecimal frozenBefore, BigDecimal frozenAfter, String remark) {
        WalletTransaction txn = new WalletTransaction();
        txn.setTxnNo(OrderNoGenerator.next("TX"));
        txn.setBizNo(bizNo);
        txn.setUserId(userId);
        txn.setOrderNo(orderNo);
        txn.setType(type.name());
        txn.setAmount(amount);
        txn.setBalanceBefore(balanceBefore);
        txn.setBalanceAfter(balanceAfter);
        txn.setFrozenBefore(frozenBefore);
        txn.setFrozenAfter(frozenAfter);
        txn.setRemark(remark);
        try {
            transactionMapper.insert(txn);
        } catch (DuplicateKeyException e) {
            // 契约附录 H-2：命中 uk_biz_no_type (biz_no, type) 即业务重复，抛 4002 而非静默重复入账
            log.warn("[wallet] 幂等键重复 bizNo={} type={} userId={}，拒绝重复入账", bizNo, type, userId);
            throw new BizException(ErrorCode.WALLET_TXN_DUPLICATE);
        }
        return txn.getTxnNo();
    }

    /** 是否已存在该业务幂等键的指定类型流水（H-3 重复结算守卫的判据） */
    private boolean existsTxn(String bizNo, TxnTypeEnum type) {
        return transactionMapper.selectCount(new LambdaQueryWrapper<WalletTransaction>()
                .eq(WalletTransaction::getBizNo, bizNo)
                .eq(WalletTransaction::getType, type.name())) > 0;
    }
}
