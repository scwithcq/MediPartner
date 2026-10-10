package com.dsc.medipartner.module.wallet.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
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
     * selectOne dispatch by the user id embedded in the query wrapper's parameter map, so that a
     * retry re-reading the same account keeps returning that account. settle queries the user
     * account and the worker account; single-account operations only ever ask for one.
     * Callers pass the user id as the first typed parameter of the wrapper.
     */
    private void stubAccounts(String userBalance, String userFrozen, String workerBalance) {
        when(accountMapper.selectOne(any())).thenAnswer(invocation -> {
            Long userId = extractUserId(invocation.getArgument(0));
            if (userId != null && userId == WORKER_ID) {
                return account(WORKER_ID, workerBalance, "0.00", 0);
            }
            return account(USER_ID, userBalance, userFrozen, 0);
        });
    }

    /** Pull the first Long value out of the MyBatis-Plus wrapper's parameter map. */
    private Long extractUserId(Object wrapper) {
        if (wrapper instanceof com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> w) {
            for (Object value : w.getParamNameValuePairs().values()) {
                if (value instanceof Long id) {
                    return id;
                }
            }
        }
        return null;
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
        stubAccounts("500.00", "200.00", "100.00");
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
}
