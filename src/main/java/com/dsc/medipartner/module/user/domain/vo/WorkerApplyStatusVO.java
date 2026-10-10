package com.dsc.medipartner.module.user.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 入驻申请状态（契约 §3.5）。
 *
 * <p><b>从未申请过时返回空对象 {@code {}}</b>（契约明确要求），
 * 前端据 {@code auditStatus} 键缺失判断「未申请」，不靠错误码——避免轮询页报错。
 */
@Data
public class WorkerApplyStatusVO {

    /** worker_profile.id */
    private Long applyId;
    /** PENDING 待审核 / PASS 已通过 / REJECT 已驳回 */
    private String auditStatus;
    /** 驳回原因（REJECT 时必有） */
    private String auditRemark;
    private LocalDateTime auditTime;
    /** 通过后的初始信用分（PASS 时返回） */
    private Integer creditScore;
    private LocalDateTime submittedAt;
}
