package com.dsc.medipartner.module.user.domain.dto;

import lombok.Data;

/**
 * §2.2 刷新 Token 请求。
 *
 * <p>刻意不加 {@code @NotBlank}：契约要求无效 refreshToken 返回 {@code 1004}
 * 而非 {@code 1001}，校验必须在 Service 层完成（见 {@code PhoneUtil} 的同类说明）。
 */
@Data
public class RefreshTokenRequest {

    private String refreshToken;
}
