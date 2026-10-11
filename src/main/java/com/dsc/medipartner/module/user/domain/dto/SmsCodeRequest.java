package com.dsc.medipartner.module.user.domain.dto;

import lombok.Data;

/**
 * §2.5 发送短信验证码请求。
 *
 * <p>手机号格式校验在 Service 层（契约要求非法格式返回 {@code 1001}），
 * 故此处不加 Bean Validation 注解。
 */
@Data
public class SmsCodeRequest {

    private String phone;

    /** 场景枚举：目前仅 {@code BIND_PHONE} */
    private String scene;
}
