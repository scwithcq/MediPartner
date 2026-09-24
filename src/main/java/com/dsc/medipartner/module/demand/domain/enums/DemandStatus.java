package com.dsc.medipartner.module.demand.domain.enums;

/**
 * 需求工单状态（接口设计文档 附录 B.8）。
 * DRAFT 为契约预留（AI 生成未提交），阶段 2 实际流转仅 OPEN -> ORDERED / CLOSED。
 */
public enum DemandStatus {

    /** 草稿（预留，未使用） */
    DRAFT,
    /** 已发布待下单 */
    OPEN,
    /** 已转正式订单（记 orderNo） */
    ORDERED,
    /** 已关闭（用户主动或超时） */
    CLOSED
}
