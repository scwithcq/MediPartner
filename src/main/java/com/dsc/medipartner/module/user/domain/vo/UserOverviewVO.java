package com.dsc.medipartner.module.user.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 「我的」页聚合数据（契约 §3.3）。
 * 一次请求渲染整页，避免小程序首屏发 4~5 个并发请求。
 */
@Data
public class UserOverviewVO {

    /** 同 §3.1 UserVO */
    private UserVO userInfo;
    /** { balance, frozen } */
    private WalletBrief wallet;
    /** 各状态订单数，键恒存在（无数据为 0） */
    private Map<String, Long> orderCount;
    /** 陪诊师入驻入口 */
    private WorkerEntry workerEntry;
    /** 未读通知数（阶段 5，无则 0） */
    private Integer unreadNotice;

    @Data
    public static class WalletBrief {
        private BigDecimal balance;
        private BigDecimal frozen;
    }

    @Data
    public static class WorkerEntry {
        /** 是否可申请入驻 */
        private Boolean canApply;
        /** PENDING / PASS / REJECT，未申请时键缺失 */
        private String auditStatus;
        /** 信用分，仅 PASS 后有值 */
        private Integer creditScore;
    }
}
