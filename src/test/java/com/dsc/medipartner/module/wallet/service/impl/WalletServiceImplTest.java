package com.dsc.medipartner.module.wallet.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.wallet.domain.entity.WalletAccount;
import com.dsc.medipartner.module.wallet.domain.entity.WalletTransaction;
import com.dsc.medipartner.module.wallet.mapper.WalletAccountMapper;
import com.dsc.medipartner.module.wallet.mapper.WalletTransactionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WalletServiceImpl unit tests, focused on appendix H-2 (fund idempotency key) and
 * H-3 (settlement retry plus duplicate-settlement guard).
 */
class WalletServiceImplTest {

    private static final long USER_ID = 9L;
    private static final long WORKER_ID = 7L;
    private static final String ORDER_NO = "MP20260921000000123456";

    private WalletAccountMapper accountMapper;
    private WalletTransactionMapper transactionMapper;
    private WalletServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WalletAccount.class);
        TableInfoHelper.initTableInfo(assistant, WalletTransaction.class);
    }

    @BeforeEach
    void setUp() {
        accountMapper = mock(WalletAccountMapper.class);
        transactionMapper = mock(WalletTransactionMapper.class);
        service = new WalletServiceImpl(accountMapper, transactionMapper);
    }

    private WalletAccount account(long userId, String balance, String frozen, int version) {
        WalletAccount a = new WalletAccount();
        a.setId(userId * 2);
        a.setUserId(userId);
        a.setBalance(new BigDecimal(balance));
        a.setFrozen(new BigDecimal(frozen));
        a.setVersion(version);
        return a;
    }

    /**
     * Stub account reads. settle reads the user account first (settleUserSide) then the worker
     * account (settleWorkerSide); single-account operations (freeze/unfreeze/refund) only ever ask
     * once, so they always get the user account. Each answer builds a fresh object so a retry
     * re-reading the same account sees the same initial state rather than an already-updated one.
     */
    private void stubAccounts(String userBalance, String userFrozen, String workerBalance) {
        when(accountMapper.selectOne(any()))
                .thenAnswer(invocation -> account(USER_ID, userBalance, userFrozen, 0))
                .thenAnswer(invocation -> account(WORKER_ID, workerBalance, "0.00", 0));
    }

    /** Stub a single-account read (freeze / unfreeze / refund paths). */
    private void stubSingleAccount(String balance, String frozen) {
        when(accountMapper.selectOne(any()))
                .thenAnswer(invocation -> account(USER_ID, balance, frozen, 0));
    }

    // ---------------- H-3: duplicate settlement guard ----------------

    /**
     * H-3 core: when a PAY transaction for this order already exists, a repeated settle call
     * must not charge again. Asserts zero account updates and zero transaction inserts.
     */
    @Test
    void settleIsIdempotentWhenPayTxnAlreadyExists() {
        when(transactionMapper.selectCount(any())).thenReturn(1L);

        service.settle(ORDER_NO, USER_ID, WORKER_ID, new BigDecimal("200.00"));

        verify(accountMapper, never()).update(any(), any());
        verify(transactionMapper, never()).insert(any(WalletTransaction.class));
    }

    /** First settlement updates both accounts and writes PAY + INCOME. */
    @Test
    void settleFirstTimeUpdatesBothAccountsAndWritesTwoTxns() {
        when(transactionMapper.selectCount(any())).thenReturn(0L);
        stubAccounts("500.00", "200.00", "100.00");
        when(accountMapper.update(any(), any())).thenReturn(1);

        service.settle(ORDER_NO, USER_ID, WORKER_ID, new BigDecimal("200.00"));

        verify(accountMapper, times(2)).update(isNull(), any());
        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(transactionMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(WalletTransaction::getType)
                .containsExactly("PAY", "INCOME");
        assertThat(captor.getAllValues())
                .extracting(WalletTransaction::getBizNo)
                .containsOnly(ORDER_NO);
    }

    /** User-side optimistic lock conflict once, then retry succeeds (H-3 MAX_RETRY). */
    @Test
    void settleRetriesUserSideOnOptimisticLockConflict() {
        when(transactionMapper.selectCount(any())).thenReturn(0L);
        // user read, user re-read on retry, then the worker read
        when(accountMapper.selectOne(any()))
                .thenAnswer(inv -> account(USER_ID, "500.00", "200.00", 0))
                .thenAnswer(inv -> account(USER_ID, "500.00", "200.00", 0))
                .thenAnswer(inv -> account(WORKER_ID, "100.00", "0.00", 0));
        when(accountMapper.update(any(), any())).thenReturn(0, 1, 1);

        service.settle(ORDER_NO, USER_ID, WORKER_ID, new BigDecimal("200.00"));

        verify(accountMapper, times(3)).update(isNull(), any());
        verify(transactionMapper, times(2)).insert(any(WalletTransaction.class));
    }

    /** Persistent user-side conflicts exhaust the retries -&gt; 4005. */
    @Test
    void settleThrows4005WhenUserSideRetryExhausted() {
        when(transactionMapper.selectCount(any())).thenReturn(0L);
        stubAccounts("500.00", "200.00", "100.00");
        when(accountMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.settle(ORDER_NO, USER_ID, WORKER_ID, new BigDecimal("200.00")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WALLET_UPDATE_CONFLICT));
        verify(transactionMapper, never()).insert(any(WalletTransaction.class));
    }

    /** Insufficient frozen amount rejects settlement so frozen never goes negative. */
    @Test
    void settleRejectsWhenFrozenInsufficient() {
        when(transactionMapper.selectCount(any())).thenReturn(0L);
        when(accountMapper.selectOne(any()))
                .thenReturn(account(USER_ID, "500.00", "50.00", 0))
                .thenReturn(account(WORKER_ID, "100.00", "0.00", 0));

        assertThatThrownBy(() -> service.settle(ORDER_NO, USER_ID, WORKER_ID, new BigDecimal("200.00")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WALLET_UPDATE_CONFLICT));
        verify(accountMapper, never()).update(any(), any());
    }

    // ---------------- H-2: fund idempotency key ----------------

    /** Successful freeze records bizNo = orderNo. */
    @Test
    void freezeRecordsBizNo() {
        when(accountMapper.selectOne(any())).thenReturn(account(USER_ID, "500.00", "0.00", 0));
        when(accountMapper.update(any(), any())).thenReturn(1);

        service.freeze(USER_ID, ORDER_NO, new BigDecimal("200.00"));

        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(transactionMapper).insert(captor.capture());
        assertThat(captor.getValue().getBizNo()).isEqualTo(ORDER_NO);
        assertThat(captor.getValue().getType()).isEqualTo("FREEZE");
        assertThat(captor.getValue().getTxnNo()).startsWith("TX");
    }

    /** Unique-key conflict surfaces as 4002 (H-2 core: 4002 must actually be thrown). */
    @Test
    void freezeThrows4002WhenDuplicateBizNo() {
        when(accountMapper.selectOne(any())).thenReturn(account(USER_ID, "500.00", "0.00", 0));
        when(accountMapper.update(any(), any())).thenReturn(1);
        when(transactionMapper.insert(any(WalletTransaction.class)))
                .thenThrow(new DuplicateKeyException("uk_biz_no_type"));

        assertThatThrownBy(() -> service.freeze(USER_ID, ORDER_NO, new BigDecimal("200.00")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WALLET_TXN_DUPLICATE));
    }

    /** Insufficient balance -&gt; 4001, no transaction written. */
    @Test
    void freezeThrows4001WhenBalanceNotEnough() {
        when(accountMapper.selectOne(any())).thenReturn(account(USER_ID, "100.00", "0.00", 0));

        assertThatThrownBy(() -> service.freeze(USER_ID, ORDER_NO, new BigDecimal("200.00")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WALLET_BALANCE_NOT_ENOUGH));
        verify(transactionMapper, never()).insert(any(WalletTransaction.class));
    }

    /** Non-positive amount -&gt; 1001. */
    @Test
    void freezeThrows1001ForNonPositiveAmount() {
        assertThatThrownBy(() -> service.freeze(USER_ID, ORDER_NO, BigDecimal.ZERO))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR));
    }

    /** Unfreeze records bizNo and retries after an optimistic lock conflict. */
    @Test
    void unfreezeRetriesAndRecordsBizNo() {
        when(accountMapper.selectOne(any())).thenReturn(account(USER_ID, "500.00", "200.00", 0));
        when(accountMapper.update(any(), any())).thenReturn(0, 1);

        service.unfreeze(USER_ID, ORDER_NO, new BigDecimal("200.00"));

        verify(accountMapper, times(2)).update(isNull(), any());
        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(transactionMapper).insert(captor.capture());
        assertThat(captor.getValue().getBizNo()).isEqualTo(ORDER_NO);
        assertThat(captor.getValue().getType()).isEqualTo("UNFREEZE");
    }

    /** Refund carries bizNo; a duplicate refund surfaces as 4002. */
    @Test
    void refundThrows4002OnDuplicate() {
        when(accountMapper.selectOne(any())).thenReturn(account(USER_ID, "500.00", "0.00", 0));
        when(accountMapper.update(any(), any())).thenReturn(1);
        when(transactionMapper.insert(any(WalletTransaction.class)))
                .thenThrow(new DuplicateKeyException("uk_biz_no_type"));

        assertThatThrownBy(() -> service.refund(USER_ID, ORDER_NO, new BigDecimal("200.00")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WALLET_TXN_DUPLICATE));
    }

    // ---------------- V14 / H-13: cumulative counters ----------------

    /** Settlement bumps cumulative income/expense, which only ever grow (contract 3.6). */
    @Test
    void settleAccumulatesTotals() {
        when(transactionMapper.selectCount(any())).thenReturn(0L);
        // settleUserSide runs first and reads the user account, then settleWorkerSide reads the worker.
        // Each call returns a fresh object so a retry cannot see stale state.
        when(accountMapper.selectOne(any())).thenAnswer(inv -> {
            WalletAccount u = account(USER_ID, "500.00", "200.00", 0);
            u.setTotalExpense(new BigDecimal("50.00"));
            return u;
        }).thenAnswer(inv -> {
            WalletAccount w = account(WORKER_ID, "100.00", "0.00", 0);
            w.setTotalIncome(new BigDecimal("300.00"));
            return w;
        });
        when(accountMapper.update(any(), any())).thenReturn(1);

        service.settle(ORDER_NO, USER_ID, WORKER_ID, new BigDecimal("200.00"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<WalletAccount>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(accountMapper, times(2)).update(isNull(), captor.capture());

        // First update is the user side (expense 50 + 200 = 250),
        // second is the worker side (income 300 + 200 = 500).
        LambdaUpdateWrapper<WalletAccount> userUpdate = captor.getAllValues().get(0);
        LambdaUpdateWrapper<WalletAccount> workerUpdate = captor.getAllValues().get(1);
        assertThat(String.valueOf(userUpdate.getSqlSet())).contains("total_expense");
        assertThat(String.valueOf(workerUpdate.getSqlSet())).contains("total_income");
        assertThat(boundValues(userUpdate)).contains(new BigDecimal("250.00"));
        assertThat(boundValues(workerUpdate)).contains(new BigDecimal("500.00"));
    }

    /** All literal values bound into a wrapper, in declaration order. */
    private java.util.List<BigDecimal> boundValues(
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WalletAccount> wrapper) {
        java.util.List<BigDecimal> values = new java.util.ArrayList<>();
        for (Object v : wrapper.getParamNameValuePairs().values()) {
            if (v instanceof BigDecimal bd) {
                values.add(bd);
            }
        }
        return values;
    }

    /** A brand new account starts with zeroed cumulative columns so reads never see null. */
    @Test
    void newAccountInitialisesCumulativeColumns() {
        when(accountMapper.selectOne(any())).thenReturn(null);
        when(accountMapper.insert(any(WalletAccount.class))).thenReturn(1);

        WalletAccount created = service.getAccount(USER_ID);

        assertThat(created.getWithdrawing()).isEqualByComparingTo("0.00");
        assertThat(created.getTotalIncome()).isEqualByComparingTo("0.00");
        assertThat(created.getTotalRecharge()).isEqualByComparingTo("0.00");
        assertThat(created.getTotalExpense()).isEqualByComparingTo("0.00");
    }

    /** Null cumulative values on legacy rows are treated as zero, not NPE. */
    @Test
    void settleToleratesNullTotalsOnLegacyRows() {
        when(transactionMapper.selectCount(any())).thenReturn(0L);
        when(accountMapper.selectOne(any())).thenAnswer(inv -> {
            WalletAccount u = account(USER_ID, "500.00", "200.00", 0);
            u.setTotalExpense(null);
            return u;
        }).thenAnswer(inv -> {
            WalletAccount w = account(WORKER_ID, "100.00", "0.00", 0);
            w.setTotalIncome(null);
            return w;
        });
        when(accountMapper.update(any(), any())).thenReturn(1);

        service.settle(ORDER_NO, USER_ID, WORKER_ID, new BigDecimal("200.00"));

        verify(accountMapper, times(2)).update(isNull(), any());
    }

    // ---------------- §3.6 wallet VO ----------------

    /** §3.6: available = balance - frozen, computed server-side so the client never subtracts. */
    @Test
    void accountVOComputesAvailableBalance() {
        when(accountMapper.selectOne(any())).thenReturn(account(USER_ID, "350.00", "100.00", 0));

        var vo = service.getAccountVO(USER_ID);

        assertThat(vo.getUserId()).isEqualTo(USER_ID);
        assertThat(vo.getBalance()).isEqualByComparingTo("350.00");
        assertThat(vo.getFrozen()).isEqualByComparingTo("100.00");
        assertThat(vo.getAvailableBalance()).isEqualByComparingTo("250.00");
    }

    /** A fresh account reports zeroes rather than nulls. */
    @Test
    void accountVODefaultsMissingColumnsToZero() {
        when(accountMapper.selectOne(any())).thenReturn(account(USER_ID, "0.00", "0.00", 0));

        var vo = service.getAccountVO(USER_ID);

        assertThat(vo.getAvailableBalance()).isEqualByComparingTo("0.00");
        assertThat(vo.getTotalIncome()).isEqualByComparingTo("0.00");
        assertThat(vo.getTotalExpense()).isEqualByComparingTo("0.00");
        assertThat(vo.getWithdrawing()).isEqualByComparingTo("0.00");
    }

    /** Null balance/frozen on a legacy row must not NPE while computing available. */
    @Test
    void accountVOToleratesNullBalance() {
        WalletAccount legacy = account(USER_ID, "0.00", "0.00", 0);
        legacy.setBalance(null);
        legacy.setFrozen(null);
        when(accountMapper.selectOne(any())).thenReturn(legacy);

        var vo = service.getAccountVO(USER_ID);

        assertThat(vo.getBalance()).isEqualByComparingTo("0.00");
        assertThat(vo.getAvailableBalance()).isEqualByComparingTo("0.00");
    }

    // ---------------- §3.7 transaction VO ----------------

    /** §3.7: each row carries the Chinese type name and direction for direct rendering. */
    @Test
    void transactionVOAddsTypeNameAndDirection() {
        com.dsc.medipartner.module.wallet.domain.entity.WalletTransaction txn =
                new com.dsc.medipartner.module.wallet.domain.entity.WalletTransaction();
        txn.setTxnNo("TX20260921100000123456");
        txn.setType("PAY");
        txn.setAmount(new BigDecimal("200.00"));
        txn.setBalanceAfter(new BigDecimal("300.00"));
        txn.setFrozenAfter(new BigDecimal("0.00"));
        txn.setOrderNo(ORDER_NO);
        txn.setRemark("订单结算扣款");
        when(accountMapper.selectOne(any())).thenReturn(account(USER_ID, "500.00", "200.00", 0));
        when(transactionMapper.selectPage(any(), any())).thenReturn(pageOf(txn));

        var result = service.pageTransactionVOs(USER_ID,
                new com.dsc.medipartner.module.wallet.domain.dto.TransactionQuery());

        assertThat(result.getRecords()).hasSize(1);
        var vo = result.getRecords().get(0);
        assertThat(vo.getType()).isEqualTo("PAY");
        assertThat(vo.getTypeName()).isEqualTo("支付");
        assertThat(vo.getDirection()).isEqualTo("OUT");
        assertThat(vo.getTxnNo()).isEqualTo("TX20260921100000123456");
        assertThat(vo.getOrderNo()).isEqualTo(ORDER_NO);
    }

    private com.baomidou.mybatisplus.core.metadata.IPage<
            com.dsc.medipartner.module.wallet.domain.entity.WalletTransaction> pageOf(
            com.dsc.medipartner.module.wallet.domain.entity.WalletTransaction txn) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<
                com.dsc.medipartner.module.wallet.domain.entity.WalletTransaction> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10, 1);
        page.setRecords(java.util.List.of(txn));
        return page;
    }
}
