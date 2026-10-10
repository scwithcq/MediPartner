package com.dsc.medipartner.module.wallet.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.util.OrderNoGenerator;
import com.dsc.medipartner.module.wallet.domain.dto.RechargeCallbackRequest;
import com.dsc.medipartner.module.wallet.domain.dto.RechargeRequest;
import com.dsc.medipartner.module.wallet.domain.entity.RechargeOrder;
import com.dsc.medipartner.module.wallet.domain.vo.RechargeCallbackVO;
import com.dsc.medipartner.module.wallet.domain.vo.RechargeVO;
import com.dsc.medipartner.module.wallet.domain.vo.WalletVO;
import com.dsc.medipartner.module.wallet.mapper.RechargeOrderMapper;
import com.dsc.medipartner.module.wallet.service.RechargeService;
import com.dsc.medipartner.module.wallet.service.WalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 充值实现（契约 §3.8、§3.9）。
 *
 * <p>幂等设计（§1.7）：{@code rechargeNo} 状态机天然幂等——已是 SUCCESS 的单重复回调
 * 直接返回首次结果，不再入账。资金流水侧还有 {@code biz_no = rechargeNo} + type=RECHARGE
 * 的唯一键作为最后防线。
 */
@Slf4j
@Service
public class RechargeServiceImpl implements RechargeService {

    /** 充值单状态 */
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String RESULT_FAILED = "FAILED";

    private final RechargeOrderMapper rechargeMapper;
    private final WalletService walletService;

    @Value("${medi.wallet.min-recharge:0.01}")
    private BigDecimal minRecharge;
    @Value("${medi.wallet.max-recharge:10000}")
    private BigDecimal maxRecharge;
    @Value("${medi.wallet.recharge-expire-minutes:15}")
    private long rechargeExpireMinutes;

    public RechargeServiceImpl(RechargeOrderMapper rechargeMapper, WalletService walletService) {
        this.rechargeMapper = rechargeMapper;
        this.walletService = walletService;
    }

    @Override
    @Transactional
    public RechargeVO create(Long userId, RechargeRequest request) {
        BigDecimal amount = request.getAmount();
        checkAmount(amount);

        RechargeOrder order = new RechargeOrder();
        order.setRechargeNo(OrderNoGenerator.next("RC"));
        order.setUserId(userId);
        order.setAmount(amount);
        order.setPresetCode(trimToNull(request.getPresetCode()));
        order.setStatus(STATUS_PENDING);
        order.setExpireAt(LocalDateTime.now().plusMinutes(rechargeExpireMinutes));
        rechargeMapper.insert(order);

        RechargeVO vo = new RechargeVO();
        vo.setRechargeNo(order.getRechargeNo());
        vo.setAmount(amount);
        vo.setStatus(STATUS_PENDING);
        // 模拟支付参数；接真实微信支付时替换为 timeStamp/nonceStr/package/signType/paySign
        Map<String, Object> payParams = new LinkedHashMap<>();
        payParams.put("mock", true);
        vo.setPayParams(payParams);
        vo.setExpireAt(order.getExpireAt());
        return vo;
    }

    @Override
    @Transactional
    public RechargeCallbackVO callback(Long userId, RechargeCallbackRequest request) {
        RechargeOrder order = rechargeMapper.selectOne(new LambdaQueryWrapper<RechargeOrder>()
                .eq(RechargeOrder::getRechargeNo, request.getRechargeNo()));
        if (order == null || !order.getUserId().equals(userId)) {
            // 充值单不存在或不属于当前用户：复用订单域 3001（契约 §3.9 指定的错误码）
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }

        String result = request.getResult().trim().toUpperCase(Locale.ROOT);
        if (!RESULT_SUCCESS.equals(result) && !RESULT_FAILED.equals(result)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "支付结果只能为 SUCCESS 或 FAILED");
        }

        RechargeCallbackVO vo = new RechargeCallbackVO();
        vo.setRechargeNo(order.getRechargeNo());

        // 幂等：已成功过的单重复回调直接返回首次结果，不再入账
        if (STATUS_SUCCESS.equals(order.getStatus())) {
            log.info("[recharge] 充值单已成功，重复回调直接返回首次结果 rechargeNo={}", order.getRechargeNo());
            vo.setStatus(STATUS_SUCCESS);
            vo.setBalance(walletService.getAccountVO(userId).getBalance());
            vo.setTxnNo(order.getTxnNo());
            return vo;
        }

        if (RESULT_FAILED.equals(result)) {
            // 只允许 PENDING -> FAILED；已 EXPIRED 的不再改写，保持超时语义
            if (STATUS_PENDING.equals(order.getStatus())) {
                rechargeMapper.update(null, new LambdaUpdateWrapper<RechargeOrder>()
                        .eq(RechargeOrder::getId, order.getId())
                        .eq(RechargeOrder::getStatus, STATUS_PENDING)
                        .set(RechargeOrder::getStatus, STATUS_FAILED));
            }
            vo.setStatus(STATUS_FAILED);
            return vo;
        }

        // SUCCESS：条件更新保证并发下只有一次真正的状态跃迁，避免重复入账
        LocalDateTime paidAt = LocalDateTime.now();
        int updated = rechargeMapper.update(null,
                new LambdaUpdateWrapper<RechargeOrder>()
                        .eq(RechargeOrder::getId, order.getId())
                        .eq(RechargeOrder::getStatus, STATUS_PENDING)
                        .set(RechargeOrder::getStatus, STATUS_SUCCESS)
                        .set(RechargeOrder::getPaidAt, paidAt));
        if (updated != 1) {
            // 并发下被其它请求先推进过状态：按幂等返回首次结果
            log.info("[recharge] 充值单状态已被并发推进，按幂等返回 rechargeNo={}", order.getRechargeNo());
            RechargeOrder latest = rechargeMapper.selectById(order.getId());
            vo.setStatus(latest.getStatus());
            vo.setTxnNo(latest.getTxnNo());
            if (STATUS_SUCCESS.equals(latest.getStatus())) {
                vo.setBalance(walletService.getAccountVO(userId).getBalance());
            }
            return vo;
        }

        // 入账走钱包域（余额变更不允许绕过 WalletService）；biz_no = rechargeNo 兜底幂等
        String txnNo = walletService.recharge(userId, order.getRechargeNo(), order.getAmount());
        rechargeMapper.update(null,
                new LambdaUpdateWrapper<RechargeOrder>()
                        .eq(RechargeOrder::getId, order.getId())
                        .set(RechargeOrder::getTxnNo, txnNo));

        WalletVO account = walletService.getAccountVO(userId);
        vo.setStatus(STATUS_SUCCESS);
        vo.setBalance(account.getBalance());
        vo.setTxnNo(txnNo);
        log.info("[recharge] 充值成功 userId={} rechargeNo={} amount={} balance={}",
                userId, order.getRechargeNo(), order.getAmount(), account.getBalance());
        return vo;
    }

    /** 金额范围由配置项决定（§3.8：0.01 ~ 10000.00），越界返回 4006 */
    private void checkAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(minRecharge) < 0 || amount.compareTo(maxRecharge) > 0) {
            throw new BizException(ErrorCode.RECHARGE_AMOUNT_ILLEGAL);
        }
        if (amount.scale() > 2) {
            throw new BizException(ErrorCode.RECHARGE_AMOUNT_ILLEGAL, "充值金额最多两位小数");
        }
    }

    private String trimToNull(String text) {
        return StringUtils.hasText(text) ? text.trim() : null;
    }
}
