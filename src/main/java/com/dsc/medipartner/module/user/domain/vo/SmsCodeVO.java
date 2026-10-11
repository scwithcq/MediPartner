package com.dsc.medipartner.module.user.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * §2.5 短信验证码发送结果。
 */
@Data
public class SmsCodeVO {

    /** 验证码有效秒数（契约 §2.5：300） */
    private Integer expiresIn;

    /** 发送时间，前端据此显示「XX 秒后可重发」 */
    private LocalDateTime sendAt;

    /**
     * 开发期回显的固定验证码（契约 §2.5：{@code medi.sms.mock=true} 时返回）。
     *
     * <p><b>生产必须为 null</b>——真实短信通道下回显验证码等于把验证码直接给攻击者。
     * 字段名带 {@code debug} 前缀，便于代码审查时一眼看出它是调试用途。
     */
    private String debugCode;
}
