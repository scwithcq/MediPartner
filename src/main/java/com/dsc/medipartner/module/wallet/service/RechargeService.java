package com.dsc.medipartner.module.wallet.service;

import com.dsc.medipartner.module.wallet.domain.dto.RechargeCallbackRequest;
import com.dsc.medipartner.module.wallet.domain.dto.RechargeRequest;
import com.dsc.medipartner.module.wallet.domain.vo.RechargeCallbackVO;
import com.dsc.medipartner.module.wallet.domain.vo.RechargeVO;

/**
 * 充值（契约 §3.8、§3.9）。
 *
 * <p>资金只在 {@code callback} 的 SUCCESS 分支入账，且走 {@code WalletService.recharge} ——
 * 余额变更不允许绕过钱包域。
 */
public interface RechargeService {

    /** §3.8 发起充值：建 PENDING 单，返回模拟支付参数与超时时间 */
    RechargeVO create(Long userId, RechargeRequest request);

    /**
     * §3.9 模拟支付回调。
     * 天然幂等：已是 SUCCESS 的单重复回调直接返回首次结果（不重复入账）；
     * 已 FAILED 的单不再接受成功。
     */
    RechargeCallbackVO callback(Long userId, RechargeCallbackRequest request);
}
