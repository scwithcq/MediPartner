package com.dsc.medipartner.module.wallet.domain.dto;

import com.dsc.medipartner.common.result.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 资金流水查询条件（契约 §3.7）。
 * 排序白名单 {@code SortWhitelist.Spec.WALLET_TXN} 只允许 {@code createdAt}（§1.8）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TransactionQuery extends PageQuery {

    /** 流水类型筛选（附录 B.3 TxnType），不传为全部 */
    private String type;

    /** 按订单号查关联流水 */
    private String orderNo;
}
