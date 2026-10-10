package com.dsc.medipartner.module.wallet.service.impl;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.wallet.domain.dto.RechargeRequest;
import com.dsc.medipartner.module.wallet.domain.dto.WithdrawApplyRequest;
import com.dsc.medipartner.module.wallet.domain.entity.WithdrawApply;
import com.dsc.medipartner.module.wallet.mapper.RechargeOrderMapper;
import com.dsc.medipartner.module.wallet.mapper.WithdrawApplyMapper;
import com.dsc.medipartner.module.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 金额范围错误码的回归测试。
 *
 * <p><b>背景（真机联调发现）</b>：{@code RechargeRequest} 曾在 DTO 上用
 * {@code @DecimalMin/@DecimalMax} 做金额校验，但 {@code GlobalExceptionHandler} 会把
 * Bean Validation 失败统一转成 <b>1001</b>，导致契约 §3.8 要求的 <b>4006</b> 永远拿不到。
 * 服务层本来有正确的 4006 分支，却因为校验在 Controller 层先失败而成为死代码。
 *
 * <p>修复方式：金额<b>范围</b>校验只放在服务层（返回 4006/4003），DTO 上不再加范围注解。
 * 本测试锁住这个行为，避免以后有人"顺手"把注解加回去。
 */
class AmountRangeErrorCodeTest {

    private static final long USER_ID = 9_000_001L;

    private RechargeOrderMapper rechargeMapper;
    private WithdrawApplyMapper withdrawMapper;
    private WalletService walletService;
    private RechargeServiceImpl rechargeService;
    private WithdrawServiceImpl withdrawService;

    @BeforeEach
    void setUp() {
        rechargeMapper = mock(RechargeOrderMapper.class);
        withdrawMapper = mock(WithdrawApplyMapper.class);
        walletService = mock(WalletService.class);
        rechargeService = new RechargeServiceImpl(rechargeMapper, walletService);
        withdrawService = new WithdrawServiceImpl(withdrawMapper, walletService);
        // @Value 注入的配置项在纯单测里手工赋值
        ReflectionTestUtils.setField(rechargeService, "minRecharge", new BigDecimal("0.01"));
        ReflectionTestUtils.setField(rechargeService, "maxRecharge", new BigDecimal("10000"));
        ReflectionTestUtils.setField(rechargeService, "rechargeExpireMinutes", 15L);
        ReflectionTestUtils.setField(withdrawService, "minWithdraw", new BigDecimal("10"));
        ReflectionTestUtils.setField(withdrawService, "maxWithdraw", new BigDecimal("5000"));
    }

    private RechargeRequest recharge(String amount) {
        RechargeRequest request = new RechargeRequest();
        request.setAmount(amount == null ? null : new BigDecimal(amount));
        return request;
    }

    private WithdrawApplyRequest withdraw(String amount) {
        WithdrawApplyRequest request = new WithdrawApplyRequest();
        request.setAmount(amount == null ? null : new BigDecimal(amount));
        request.setChannel("WECHAT");
        return request;
    }

    // ---------------- §3.8 充值 -> 4006 ----------------

    /** 超上限必须是 4006，不能退化成 1001（这正是真机暴露的问题）。 */
    @Test
    void rechargeAboveMaxYields4006NotParamError() {
        assertThatThrownBy(() -> rechargeService.create(USER_ID, recharge("99999.00")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RECHARGE_AMOUNT_ILLEGAL));
    }

    @Test
    void rechargeBelowMinYields4006() {
        assertThatThrownBy(() -> rechargeService.create(USER_ID, recharge("0.001")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RECHARGE_AMOUNT_ILLEGAL));
    }

    @Test
    void rechargeNullAmountYields4006() {
        assertThatThrownBy(() -> rechargeService.create(USER_ID, recharge(null)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RECHARGE_AMOUNT_ILLEGAL));
    }

    @Test
    void rechargeWithThreeDecimalsYields4006() {
        assertThatThrownBy(() -> rechargeService.create(USER_ID, recharge("10.123")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RECHARGE_AMOUNT_ILLEGAL));
    }

    /** 边界值必须放行：恰好 0.01 与恰好 10000.00。 */
    @Test
    void rechargeBoundaryValuesAreAccepted() {
        when(rechargeMapper.insert(any(com.dsc.medipartner.module.wallet.domain.entity.RechargeOrder.class)))
                .thenReturn(1);
        assertThat(rechargeService.create(USER_ID, recharge("0.01")).getStatus()).isEqualTo("PENDING");
        assertThat(rechargeService.create(USER_ID, recharge("10000.00")).getStatus()).isEqualTo("PENDING");
    }

    // ---------------- §7.18 提现 -> 4003 ----------------

    @Test
    void withdrawAboveMaxYields4003() {
        assertThatThrownBy(() -> withdrawService.apply(USER_ID, withdraw("5000.01")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WITHDRAW_AMOUNT_ILLEGAL));
    }

    @Test
    void withdrawBelowMinYields4003() {
        assertThatThrownBy(() -> withdrawService.apply(USER_ID, withdraw("9.99")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WITHDRAW_AMOUNT_ILLEGAL));
    }

    @Test
    void withdrawWithThreeDecimalsYields4003() {
        assertThatThrownBy(() -> withdrawService.apply(USER_ID, withdraw("10.123")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WITHDRAW_AMOUNT_ILLEGAL));
    }

    /** 金额越界的检查必须早于「已有处理中」检查，否则报错顺序会让用户困惑。 */
    @Test
    void withdrawAmountCheckRunsBeforePendingCheck() {
        when(withdrawMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> withdrawService.apply(USER_ID, withdraw("1.00")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WITHDRAW_AMOUNT_ILLEGAL));
    }

    /** BANK 是预留渠道，按契约 §7.18 返回 1001（与金额越界的 4003 区分）。 */
    @Test
    void withdrawBankChannelYields1001() {
        WithdrawApplyRequest request = withdraw("100.00");
        request.setChannel("BANK");

        assertThatThrownBy(() -> withdrawService.apply(USER_ID, request))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR));
    }

    /** 渠道大小写不敏感：小写 wechat 应被接受（避免前端拼写大小写导致误拒）。 */
    @Test
    void withdrawChannelIsCaseInsensitive() {
        when(withdrawMapper.selectCount(any())).thenReturn(0L);
        when(withdrawMapper.insert(any(WithdrawApply.class))).thenReturn(1);
        WithdrawApplyRequest request = withdraw("100.00");
        request.setChannel("wechat");

        assertThat(withdrawService.apply(USER_ID, request).getStatus()).isEqualTo("PENDING");
    }
}
