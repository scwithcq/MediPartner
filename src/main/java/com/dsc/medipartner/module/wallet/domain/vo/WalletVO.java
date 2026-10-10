package com.dsc.medipartner.module.wallet.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 钱包账户（契约 §3.6）。
 *
 * <p>阶段 1.5 由裸 {@code WalletAccount} 实体改造而来：<b>不再暴露</b> {@code id} 与乐观锁
 * {@code version}。
 *
 * <p>口径（附录 B.3）：{@code balance} 是<b>总余额</b>，{@code frozen} 是其中被占用的部分，
 * <b>可用余额 = balance - frozen</b>，前端一律用 {@code availableBalance}，不要自己相减。
 */
@Data
public class WalletVO {

    private Long userId;
    /** 总余额（两位小数字符串） */
    private BigDecimal balance;
    /** 冻结金额（下单未结算部分） */
    private BigDecimal frozen;
    /** 可用余额 = balance - frozen（§3.6 备注要求前端直接用它） */
    private BigDecimal availableBalance;
    /** 累计收入（陪诊师视角，V14） */
    private BigDecimal totalIncome;
    /** 累计支出（V14） */
    private BigDecimal totalExpense;
    /** 提现处理中金额（阶段 4 启用，V14 列已就位） */
    private BigDecimal withdrawing;
    /** 最近变动时间 */
    private LocalDateTime updatedAt;
}
