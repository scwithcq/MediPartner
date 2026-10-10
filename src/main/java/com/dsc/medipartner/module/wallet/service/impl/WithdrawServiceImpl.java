package com.dsc.medipartner.module.wallet.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.util.OrderNoGenerator;
import com.dsc.medipartner.module.wallet.domain.dto.WithdrawApplyRequest;
import com.dsc.medipartner.module.wallet.domain.dto.WithdrawQuery;
import com.dsc.medipartner.module.wallet.domain.entity.WithdrawApply;
import com.dsc.medipartner.module.wallet.domain.vo.WithdrawVO;
import com.dsc.medipartner.module.wallet.mapper.WithdrawApplyMapper;
import com.dsc.medipartner.module.wallet.service.WalletService;
import com.dsc.medipartner.module.wallet.service.WithdrawService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 提现实现（契约 §7.18、§7.19）。
 *
 * <p>关键决策：<b>申请时即扣减 balance 并累加 withdrawing</b>，而不是等审核通过才扣。
 * 理由：若等审核通过才扣，用户可在等待期内把钱花掉，导致审核通过时余额不足——
 * 管理端要么打款失败要么垫付，都是资损。先冻结（withdrawing）能保证「待打款的单一定有钱」。
 */
@Slf4j
@Service
public class WithdrawServiceImpl implements WithdrawService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String CHANNEL_WECHAT = "WECHAT";
    /** 手续费恒为 0（本项目不抽成，字段保留以便将来接真实通道不改契约） */
    private static final BigDecimal FEE = BigDecimal.ZERO.setScale(2);

    private final WithdrawApplyMapper withdrawMapper;
    private final WalletService walletService;

    @Value("${medi.wallet.min-withdraw:10}")
    private BigDecimal minWithdraw;
    @Value("${medi.wallet.max-withdraw:5000}")
    private BigDecimal maxWithdraw;

    public WithdrawServiceImpl(WithdrawApplyMapper withdrawMapper, WalletService walletService) {
        this.withdrawMapper = withdrawMapper;
        this.walletService = walletService;
    }

    @Override
    @Transactional
    public WithdrawVO apply(Long workerId, WithdrawApplyRequest request) {
        BigDecimal amount = request.getAmount();
        checkAmount(amount);

        String channel = request.getChannel().trim().toUpperCase(Locale.ROOT);
        if (!CHANNEL_WECHAT.equals(channel)) {
            // BANK 为预留渠道，契约 §7.18 明确传入返回 1001
            throw new BizException(ErrorCode.PARAM_ERROR, "暂不支持的提现渠道：" + request.getChannel());
        }

        // §7.18 备注 3：同一时刻只允许一笔处理中，避免并发扣款歧义
        long pending = withdrawMapper.selectCount(new LambdaQueryWrapper<WithdrawApply>()
                .eq(WithdrawApply::getWorkerId, workerId)
                .eq(WithdrawApply::getStatus, STATUS_PENDING));
        if (pending > 0) {
            throw new BizException(ErrorCode.WITHDRAW_IN_PROGRESS);
        }

        String withdrawNo = OrderNoGenerator.next("WD");
        // 先出账（内部校验可用余额，不足抛 4001），再建单；同事务保证一致
        walletService.withdrawApply(workerId, withdrawNo, amount);

        BigDecimal actualAmount = amount.subtract(FEE);
        WithdrawApply apply = new WithdrawApply();
        apply.setWithdrawNo(withdrawNo);
        apply.setWorkerId(workerId);
        apply.setAmount(amount);
        apply.setFee(FEE);
        apply.setActualAmount(actualAmount);
        apply.setChannel(channel);
        apply.setStatus(STATUS_PENDING);
        apply.setRemark(trimToNull(request.getRemark()));
        apply.setVersion(0);
        withdrawMapper.insert(apply);

        log.info("[withdraw] 提现申请已提交 workerId={} withdrawNo={} amount={}",
                workerId, withdrawNo, amount);
        return toVO(apply);
    }

    @Override
    public IPage<WithdrawVO> pageWithdraws(Long workerId, WithdrawQuery query) {
        Page<WithdrawApply> page = new Page<>(query.getEffectivePageNum(), query.getEffectivePageSize());
        LambdaQueryWrapper<WithdrawApply> wrapper = new LambdaQueryWrapper<WithdrawApply>()
                .eq(WithdrawApply::getWorkerId, workerId);
        if (StringUtils.hasText(query.getStatus())) {
            wrapper.eq(WithdrawApply::getStatus, query.getStatus().trim().toUpperCase(Locale.ROOT));
        }
        // §7.19：默认 createdAt desc（该表未列入 §1.8 排序白名单，固定排序避免开注入面）
        wrapper.orderByDesc(WithdrawApply::getCreatedAt).orderByDesc(WithdrawApply::getId);

        IPage<WithdrawApply> source = withdrawMapper.selectPage(page, wrapper);
        List<WithdrawVO> records = new ArrayList<>(source.getRecords().size());
        for (WithdrawApply apply : source.getRecords()) {
            records.add(toVO(apply));
        }
        Page<WithdrawVO> result = new Page<>(source.getCurrent(), source.getSize(), source.getTotal());
        result.setRecords(records);
        return result;
    }

    /** 金额上下限由配置决定（§7.18：低于下限或超单笔上限返回 4003） */
    private void checkAmount(BigDecimal amount) {
        if (amount == null) {
            throw new BizException(ErrorCode.WITHDRAW_AMOUNT_ILLEGAL);
        }
        if (amount.scale() > 2) {
            throw new BizException(ErrorCode.WITHDRAW_AMOUNT_ILLEGAL, "提现金额最多两位小数");
        }
        if (amount.compareTo(minWithdraw) < 0) {
            throw new BizException(ErrorCode.WITHDRAW_AMOUNT_ILLEGAL,
                    "提现金额不得低于 " + minWithdraw.toPlainString());
        }
        if (amount.compareTo(maxWithdraw) > 0) {
            throw new BizException(ErrorCode.WITHDRAW_AMOUNT_ILLEGAL,
                    "单笔提现不得超过 " + maxWithdraw.toPlainString());
        }
    }

    private WithdrawVO toVO(WithdrawApply apply) {
        WithdrawVO vo = new WithdrawVO();
        vo.setWithdrawNo(apply.getWithdrawNo());
        vo.setAmount(apply.getAmount());
        vo.setFee(apply.getFee());
        vo.setActualAmount(apply.getActualAmount());
        vo.setChannel(apply.getChannel());
        vo.setStatus(apply.getStatus());
        vo.setAuditRemark(apply.getAuditRemark());
        vo.setAuditTime(apply.getAuditTime());
        vo.setFinishedAt(apply.getFinishedAt());
        vo.setCreatedAt(apply.getCreatedAt());
        return vo;
    }

    private String trimToNull(String text) {
        return StringUtils.hasText(text) ? text.trim() : null;
    }
}
