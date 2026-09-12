package com.dsc.medipartner.module.wallet.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.common.result.PageQuery;
import com.dsc.medipartner.common.result.PageResult;
import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.module.wallet.domain.entity.WalletAccount;
import com.dsc.medipartner.module.wallet.domain.entity.WalletTransaction;
import com.dsc.medipartner.module.wallet.service.WalletService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping("/account")
    public R<WalletAccount> account() {
        return R.ok(walletService.getAccount(UserContext.userId()));
    }

    @GetMapping("/transactions")
    public R<PageResult<WalletTransaction>> transactions(PageQuery query) {
        IPage<WalletTransaction> page = walletService.pageTransactions(
                UserContext.userId(), query.getPageNum(), query.getPageSize());
        return R.ok(PageResult.of(page));
    }
}
