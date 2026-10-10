package com.dsc.medipartner.module.wallet.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.result.SortWhitelist;
import com.dsc.medipartner.common.util.OrderNoGenerator;
import com.dsc.medipartner.module.wallet.domain.dto.TransactionQuery;
import com.dsc.medipartner.module.wallet.domain.entity.WalletAccount;
import com.dsc.medipartner.module.wallet.domain.entity.WalletTransaction;
import com.dsc.medipartner.module.wallet.domain.enums.TxnTypeEnum;
import com.dsc.medipartner.module.wallet.domain.vo.TransactionVO;
import com.dsc.medipartner.module.wallet.domain.vo.WalletVO;
import com.dsc.medipartner.module.wallet.mapper.WalletAccountMapper;
import com.dsc.medipartner.module.wallet.mapper.WalletTransactionMapper;
import com.dsc.medipartner.module.wallet.service.WalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class WalletServiceImpl implements WalletService {

    private static final int MAX_RETRY = 3;
    private static final DateTimeFormatter UPDATED_SINCE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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

    /** §3.6 钱包账户 VO：不暴露 id/version，可用余额由服务端算好（前端不自己相减） */
    @Override
    public WalletVO getAccountVO(Long userId) {
        WalletAccount account = getOrCreate(userId);
        return toWalletVO(account);
    }

    private WalletVO toWalletVO(WalletAccount account) {
        WalletVO vo = new WalletVO();
        vo.setUserId(account.getUserId());
        BigDecimal balance = nz(account.getBalance());
        BigDecimal frozen = nz(account.getFrozen());
        vo.setBalance(balance);
        vo.setFrozen(frozen);
        // 附录 B.3：可用余额 = 总余额 - 冻结额
        vo.setAvailableBalance(balance.subtract(frozen));
        vo.setTotalIncome(nz(account.getTotalIncome()));
        vo.setTotalExpense(nz(account.getTotalExpense()));
        vo.setTotalRecharge(nz(account.getTotalRecharge()));
        vo.setWithdrawing(nz(account.getWithdrawing()));
        vo.setUpdatedAt(account.getUpdatedAt());
        return vo;
    }

    /** §3.7 资金流水 VO：补 typeName 中文名与 direction 方向 */
    @Override
    public IPage<TransactionVO> pageTransactionVOs(Long userId, TransactionQuery query) {
        IPage<WalletTransaction> page = pageTransactions(userId, query);
        List<TransactionVO> records = new ArrayList<>(page.getRecords().size());
        for (WalletTransaction txn : page.getRecords()) {
            records.add(toTransactionVO(txn));
        }
        Page<TransactionVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(records);
        return result;
    }

    private TransactionVO toTransactionVO(WalletTransaction txn) {
        TransactionVO vo = new TransactionVO();
        vo.setTxnNo(txn.getTxnNo());
        vo.setType(txn.getType());
        vo.setTypeName(TxnTypeEnum.nameOf(txn.getType()));
        vo.setDirection(TxnTypeEnum.directionOf(txn.getType()));
        vo.setAmount(txn.getAmount());
        vo.setBalanceAfter(txn.getBalanceAfter());
        vo.setFrozenAfter(txn.getFrozenAfter());
        vo.setOrderNo(txn.getOrderNo());
        vo.setRemark(txn.getRemark());
        vo.setCreatedAt(txn.getCreatedAt());
        return vo;
    }

    @Override
    public IPage<WalletTransaction> pageTransactions(Long userId, TransactionQuery query) {
        LambdaQueryWrapper<WalletTransaction> wrapper = new LambdaQueryWrapper<WalletTransaction>()
                .eq(WalletTransaction::getUserId, userId)
                .eq(StringUtils.hasText(query.getType()), WalletTransaction::getType,
                        query.getType() == null ? null : query.getType().trim().toUpperCase(Locale.ROOT))
                .eq(StringUtils.hasText(query.getOrderNo()), WalletTransaction::getOrderNo, query.getOrderNo());

        // §1.9 增量轮询：updated_at 严格大于 updatedSince（该表只有 created_at，故以 createdAt 为口径）
        LocalDateTime since = parseUpdatedSince(query.getUpdatedSince());
        if (since != null) {
            wrapper.gt(WalletTransaction::getCreatedAt, since);
        }

        // §1.8 白名单：列名只可能来自枚举常量，前端原始字符串绝不进入 SQL
        SortWhitelist.Order order = SortWhitelist.Spec.WALLET_TXN
                .resolve(query.getSortBy(), query.getOrder());
        wrapper.last("ORDER BY " + order.sqlColumn() + (order.asc() ? " ASC" : " DESC") + ", id DESC");

        Page<WalletTransaction> page = new Page<>(query.getEffectivePageNum(), query.getEffectivePageSize());
        return transactionMapper.selectPage(page, wrapper);
    }

    private LocalDateTime parseUpdatedSince(String updatedSince) {
        if (!StringUtils.hasText(updatedSince)) {
            return null;
        }
        try {
            return LocalDateTime.parse(updatedSince.trim(), UPDATED_SINCE_FORMAT);
        } catch (DateTimeParseException e) {
            // 格式非法忽略该参数，不报错（§1.3 容错语义）
            log.warn("[wallet] updatedSince 格式非法，已忽略: {}", updatedSince);
            return null;
        }
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
                    // V14 H-13：累计支出只增不减（§3.6）
                    .set(WalletAccount::getTotalExpense, nz(user.getTotalExpense()).add(amount))
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
                    // V14 H-13：累计收入只增不减（§3.6、§7.17）
                    .set(WalletAccount::getTotalIncome, nz(worker.getTotalIncome()).add(amount))
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

    // ---------------- 阶段 4：充值入账 / 提现出账与回退 ----------------

    /**
     * §3.9 充值入账。幂等由 {@code biz_no = rechargeNo} + type=RECHARGE 的唯一键兜底：
     * 重复回调会命中 4002，调用方（RechargeService）据状态机先行拦截。
     */
    @Override
    @Transactional
    public String recharge(Long userId, String rechargeNo, BigDecimal amount) {
        checkAmount(amount);
        for (int i = 0; i < MAX_RETRY; i++) {
            WalletAccount account = getOrCreate(userId);
            int updated = accountMapper.update(null, new LambdaUpdateWrapper<WalletAccount>()
                    .eq(WalletAccount::getUserId, userId)
                    .eq(WalletAccount::getVersion, account.getVersion())
                    .set(WalletAccount::getBalance, account.getBalance().add(amount))
                    // 累计充值只增不减
                    .set(WalletAccount::getTotalRecharge, nz(account.getTotalRecharge()).add(amount))
                    .set(WalletAccount::getVersion, account.getVersion() + 1));
            if (updated == 1) {
                return record(userId, null, rechargeNo, TxnTypeEnum.RECHARGE, amount,
                        account.getBalance(), account.getBalance().add(amount),
                        account.getFrozen(), account.getFrozen(), "充值");
            }
        }
        throw new BizException(ErrorCode.WALLET_UPDATE_CONFLICT);
    }

    /**
     * §7.18 提现出账（申请时调用）。
     * 语义：{@code balance -= amount} 且 {@code withdrawing += amount}——
     * 钱离开可用余额但仍属于用户（处理中），驳回时原路退回。
     */
    @Override
    @Transactional
    public void withdrawApply(Long userId, String withdrawNo, BigDecimal amount) {
        checkAmount(amount);
        for (int i = 0; i < MAX_RETRY; i++) {
            WalletAccount account = getOrCreate(userId);
            // 提现只能用「可用余额」= balance - frozen，不能用被订单冻结的钱
            if (account.getBalance().subtract(account.getFrozen()).compareTo(amount) < 0) {
                throw new BizException(ErrorCode.WALLET_BALANCE_NOT_ENOUGH);
            }
            int updated = accountMapper.update(null, new LambdaUpdateWrapper<WalletAccount>()
                    .eq(WalletAccount::getUserId, userId)
                    .eq(WalletAccount::getVersion, account.getVersion())
                    .set(WalletAccount::getBalance, account.getBalance().subtract(amount))
                    .set(WalletAccount::getWithdrawing, nz(account.getWithdrawing()).add(amount))
                    .set(WalletAccount::getVersion, account.getVersion() + 1));
            if (updated == 1) {
                record(userId, null, withdrawNo, TxnTypeEnum.WITHDRAW, amount,
                        account.getBalance(), account.getBalance().subtract(amount),
                        account.getFrozen(), account.getFrozen(), "提现申请");
                return;
            }
        }
        throw new BizException(ErrorCode.WALLET_UPDATE_CONFLICT);
    }

    /**
     * §9.21 提现驳回回退：余额加回、处理中金额减掉，写 REFUND 流水。
     * 与 {@link #withdrawApply} 的 WITHDRAW 流水共享 {@code biz_no = withdrawNo}，靠 type 区分。
     */
    @Override
    @Transactional
    public void withdrawReject(Long userId, String withdrawNo, BigDecimal amount) {
        checkAmount(amount);
        for (int i = 0; i < MAX_RETRY; i++) {
            WalletAccount account = getOrCreate(userId);
            int updated = accountMapper.update(null, new LambdaUpdateWrapper<WalletAccount>()
                    .eq(WalletAccount::getUserId, userId)
                    .eq(WalletAccount::getVersion, account.getVersion())
                    .set(WalletAccount::getBalance, account.getBalance().add(amount))
                    // 处理中金额可能因历史数据为负，取下界 0 避免出现负值
                    .set(WalletAccount::getWithdrawing,
                            nz(account.getWithdrawing()).subtract(amount).max(BigDecimal.ZERO))
                    .set(WalletAccount::getVersion, account.getVersion() + 1));
            if (updated == 1) {
                record(userId, null, withdrawNo, TxnTypeEnum.REFUND, amount,
                        account.getBalance(), account.getBalance().add(amount),
                        account.getFrozen(), account.getFrozen(), "提现驳回退回");
                return;
            }
        }
        throw new BizException(ErrorCode.WALLET_UPDATE_CONFLICT);
    }

    /** 累计列容错取值：历史数据可能为 null，按 0 处理 */
    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
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
        // V14 累计列：DB 有默认值，但实体显式赋值避免回读前为 null
        created.setWithdrawing(BigDecimal.ZERO);
        created.setTotalIncome(BigDecimal.ZERO);
        created.setTotalRecharge(BigDecimal.ZERO);
        created.setTotalExpense(BigDecimal.ZERO);
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
