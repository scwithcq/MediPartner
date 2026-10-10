package com.dsc.medipartner.module.wallet.domain.dto;

import com.dsc.medipartner.common.result.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 提现记录查询（契约 §7.19）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WithdrawQuery extends PageQuery {

    /** PENDING / SUCCESS / REJECT，不传看全部 */
    private String status;
}
