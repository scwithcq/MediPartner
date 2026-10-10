package com.dsc.medipartner.module.wallet.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.common.result.PageResult;
import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.module.wallet.domain.dto.RechargeCallbackRequest;
import com.dsc.medipartner.module.wallet.domain.dto.RechargeRequest;
import com.dsc.medipartner.module.wallet.domain.dto.TransactionQuery;
import com.dsc.medipartner.module.wallet.domain.dto.WithdrawApplyRequest;
import com.dsc.medipartner.module.wallet.domain.dto.WithdrawQuery;
import com.dsc.medipartner.module.wallet.domain.vo.RechargeCallbackVO;
import com.dsc.medipartner.module.wallet.domain.vo.RechargeVO;
import com.dsc.medipartner.module.wallet.domain.vo.TransactionVO;
import com.dsc.medipartner.module.wallet.domain.vo.WalletVO;
import com.dsc.medipartner.module.wallet.domain.vo.WithdrawVO;
import com.dsc.medipartner.module.wallet.service.RechargeService;
import com.dsc.medipartner.module.wallet.service.WalletService;
import com.dsc.medipartner.module.wallet.service.WithdrawService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 钱包相关（契约 §3.6~§3.9）。用户与陪诊师共用，路径前缀已收敛到 {@code /api/wallet/**}。
 */
@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private final WalletService walletService;
    private final RechargeService rechargeService;
    private final WithdrawService withdrawService;

    public WalletController(WalletService walletService, RechargeService rechargeService,
                            WithdrawService withdrawService) {
        this.walletService = walletService;
        this.rechargeService = rechargeService;
        this.withdrawService = withdrawService;
    }

    /** §3.6 钱包账户（VO：不暴露 version，含 availableBalance） */
    @GetMapping("/account")
    public R<WalletVO> account() {
        return R.ok(walletService.getAccountVO(UserContext.userId()));
    }

    /** §3.7 资金流水（VO：含 typeName/direction，支持 type/orderNo 筛选） */
    @GetMapping("/transactions")
    public R<PageResult<TransactionVO>> transactions(TransactionQuery query) {
        IPage<TransactionVO> page = walletService.pageTransactionVOs(UserContext.userId(), query);
        return R.ok(PageResult.of(page));
    }

    /** §3.8 发起充值：建 PENDING 单并返回模拟支付参数 */
    @PostMapping("/recharge")
    public R<RechargeVO> recharge(@Valid @RequestBody RechargeRequest request) {
        return R.ok(rechargeService.create(UserContext.userId(), request));
    }

    /** §3.9 模拟支付回调：SUCCESS 时同事务入账，天然幂等 */
    @PostMapping("/recharge/callback")
    public R<RechargeCallbackVO> rechargeCallback(@Valid @RequestBody RechargeCallbackRequest request) {
        return R.ok(rechargeService.callback(UserContext.userId(), request));
    }

    /** §7.18 申请提现（陪诊师）：同一时刻只允许一笔处理中 */
    @PostMapping("/withdraw/apply")
    public R<WithdrawVO> withdrawApply(@Valid @RequestBody WithdrawApplyRequest request) {
        return R.ok(withdrawService.apply(UserContext.userId(), request));
    }

    /** §7.19 提现记录（陪诊师） */
    @GetMapping("/withdraw/list")
    public R<PageResult<WithdrawVO>> withdrawList(WithdrawQuery query) {
        IPage<WithdrawVO> page = withdrawService.pageWithdraws(UserContext.userId(), query);
        return R.ok(PageResult.of(page));
    }
}
