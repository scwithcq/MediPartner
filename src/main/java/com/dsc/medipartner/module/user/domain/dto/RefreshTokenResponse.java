package com.dsc.medipartner.module.user.domain.dto;

import lombok.Data;

/**
 * §2.2 刷新 Token 响应。
 *
 * <p>{@code refreshToken} 为<b>一次性</b>：每次刷新都返回新值，旧值立即失效，
 * 前端必须覆盖本地存储（契约 §2.2）。
 */
@Data
public class RefreshTokenResponse {

    private String token;
    private String refreshToken;
    /** accessToken 剩余有效秒数（= 7 天 * 24h * 3600s，与 refreshToken 有效期一致） */
    private Long expiresIn;
}
