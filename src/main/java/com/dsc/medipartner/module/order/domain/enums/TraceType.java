package com.dsc.medipartner.module.order.domain.enums;

/**
 * 留痕类型（契约 §5.9 / §7.13 type）：TRACE 服务过程中的留痕，VOUCHER 结束服务时提交的凭证。
 * 两类同存 order_trace 表，用 type 区分；用户侧与陪诊师侧列表同源展示。
 */
public enum TraceType {

    /** 过程留痕 */
    TRACE,
    /** 结束凭证 */
    VOUCHER
}
