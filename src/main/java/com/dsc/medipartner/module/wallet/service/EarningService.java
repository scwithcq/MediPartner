package com.dsc.medipartner.module.wallet.service;

import com.dsc.medipartner.module.wallet.domain.vo.EarningVO;

/**
 * 收益概览（契约 §7.17）。
 * 数据来源是 {@code wallet_account} 的累计列与 {@code wallet_transaction} 的 INCOME 流水，
 * 不反查订单表，避免与订单模块形成环依赖。
 */
public interface EarningService {

    /**
     * @param workerId 陪诊师用户 ID
     * @param period   {@code WEEK}（默认）/ {@code MONTH} / {@code YEAR} / {@code ALL}
     * @param txnPageNum  最近流水页码（仅作用于 recentTxns）
     * @param txnPageSize 最近流水每页条数
     */
    EarningVO getEarning(Long workerId, String period, long txnPageNum, long txnPageSize);
}
