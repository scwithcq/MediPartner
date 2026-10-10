package com.dsc.medipartner.module.ticket.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发起申诉响应（契约 §5.14）。
 */
@Data
public class CreateAppealVO {

    private String appealNo;
    /** 固定 PENDING */
    private String status;
    private LocalDateTime createdAt;
}
