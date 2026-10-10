package com.dsc.medipartner.module.wallet.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.common.result.PageResult;
import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.module.wallet.domain.dto.TransactionQuery;
import com.dsc.medipartner.module.wallet.domain.vo.TransactionVO;
import com.dsc.medipartner.module.wallet.domain.vo.WalletVO;
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
}
