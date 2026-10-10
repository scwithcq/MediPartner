package com.dsc.medipartner.module.wallet.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 收益概览（契约 §7.17）。
 * 本平台不抽成，结算金额等于订单金额；{@code avgIncome} 仍单独下发，
 * 方便前端做「本周单均」卡片而不必自己算。
 */
@Data
public class EarningVO {

    /** 累计收益，全部 INCOME 流水合计 */
    private BigDecimal totalIncome;
    /** 所选周期收益 */
    private BigDecimal periodIncome;
    /** 本月收益，不随 period 变化，首屏常驻 */
    private BigDecimal monthIncome;
    /** 可提现余额（wallet_account.balance） */
    private BigDecimal availableBalance;
    /** 冻结中金额（订单未结算部分） */
    private BigDecimal frozenAmount;
    /** 提现处理中金额 */
    private BigDecimal withdrawing;
    /** 所选周期完成单数 */
    private Long orderCount;
    /** 所选周期单均收益 */
    private BigDecimal avgIncome;
    /** 收益曲线：WEEK/MONTH 按天、YEAR 按月 */
    private List<ChartPoint> chart;
    /** 最近资金流水，元素结构同 §3.7 */
    private List<TransactionVO> recentTxns;

    @Data
    public static class ChartPoint {
        private LocalDate date;
        /** YEAR 周期时为月份首日 */
        private BigDecimal amount;
        private Long count;
        private LocalDateTime periodStart;
    }
}
