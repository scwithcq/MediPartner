package com.dsc.medipartner.module.wallet.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.module.wallet.domain.dto.WithdrawApplyRequest;
import com.dsc.medipartner.module.wallet.domain.dto.WithdrawQuery;
import com.dsc.medipartner.module.wallet.domain.vo.WithdrawVO;

/**
 * 提现（契约 §7.18、§7.19）。
 *
 * <p>资金出口三条铁律：
 * <ol>
 *   <li>全链路<b>一个本地事务</b>：扣余额 + 累加 withdrawing + 建单 + 写流水，任一失败整体回滚</li>
 *   <li>同一时刻<b>只允许一笔处理中</b>（4007），避免并发扣款歧义</li>
 *   <li>{@code withdraw_no} 唯一索引 + 流水 {@code biz_no} 复合唯一键，双重幂等兜底</li>
 * </ol>
 */
public interface WithdrawService {

    /** §7.18 申请提现 */
    WithdrawVO apply(Long workerId, WithdrawApplyRequest request);

    /** §7.19 提现记录（默认 createdAt desc） */
    IPage<WithdrawVO> pageWithdraws(Long workerId, WithdrawQuery query);
}
