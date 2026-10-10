package com.dsc.medipartner.module.wallet.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资金流水（契约 §3.7）。
 *
 * <p>阶段 1.5 由裸 {@code WalletTransaction} 实体收敛而来：去掉 {@code userId} 与
 * {@code balanceBefore}/{@code frozenBefore}/{@code relatedTxnNo}（变动前快照只留给管理端审计接口）。
 */
@Data
public class TransactionVO {

    /** 流水号，格式 TX + yyyyMMddHHmmss + 6 位随机，全表唯一 */
    private String txnNo;
    /** FREEZE / UNFREEZE / PAY / INCOME / REFUND / RECHARGE */
    private String type;
    /** 中文名（冻结/解冻/支付/收入/退款/充值），前端免映射 */
    private String typeName;
    /** IN 入账 / OUT 出账 / LOCK 冻结类，前端据此显示正负号与颜色 */
    private String direction;
    /** 发生额（正数，方向由 direction 表达） */
    private BigDecimal amount;
    /** 变动后可用余额 */
    private BigDecimal balanceAfter;
    /** 变动后冻结金额 */
    private BigDecimal frozenAfter;
    /** 关联订单号，无则键缺失 */
    private String orderNo;
    private String remark;
    private LocalDateTime createdAt;
}
