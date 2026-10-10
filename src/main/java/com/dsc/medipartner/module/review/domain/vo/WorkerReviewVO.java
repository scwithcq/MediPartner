package com.dsc.medipartner.module.review.domain.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 陪诊师收到的评价（契约 §7.20）：在 §6.3 基础上追加订单信息与回复能力位。
 * 汇总统计不在此返回，统一走 §7.2 WorkerProfileVO，避免同一份统计两个数据源。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WorkerReviewVO extends PublicReviewVO {

    /** 关联订单号，仅对自己可见 */
    private String orderNo;
    /** 该单预约服务时间，帮陪诊师回忆起是哪一单 */
    private LocalDateTime orderServiceTime;
    /** 未回复且评价产生后 7 天内 */
    private Boolean canReply;
    /** 恶意差评申诉，阶段 4 才可能为 true，阶段 3 恒为 false */
    private Boolean canAppeal;
    /** 该条评价对信用分的影响，带正负号如 "+2"、"-5"；查不到流水时键缺失，前端不要兜底 +0 */
    private String impactScore;
}
