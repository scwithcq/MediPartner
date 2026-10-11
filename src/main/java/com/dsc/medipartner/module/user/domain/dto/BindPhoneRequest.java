package com.dsc.medipartner.module.user.domain.dto;

import lombok.Data;

/**
 * §2.6 短信验证码绑定手机号请求。
 *
 * <p>校验全部在 Service 层：契约要求 {@code 2002}（已被占用）/ {@code 2004}（验证码错误）
 * 等特定错误码，用 Bean Validation 会被 {@code GlobalExceptionHandler} 压成 {@code 1001}。
 */
@Data
public class BindPhoneRequest {

    private String phone;
    private String smsCode;
}
