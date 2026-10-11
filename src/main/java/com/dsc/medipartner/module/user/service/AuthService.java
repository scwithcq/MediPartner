package com.dsc.medipartner.module.user.service;

import com.dsc.medipartner.module.user.domain.dto.BindPhoneRequest;
import com.dsc.medipartner.module.user.domain.dto.LoginRequest;
import com.dsc.medipartner.module.user.domain.dto.LoginResponse;
import com.dsc.medipartner.module.user.domain.dto.RefreshTokenRequest;
import com.dsc.medipartner.module.user.domain.dto.RefreshTokenResponse;
import com.dsc.medipartner.module.user.domain.dto.WechatPhoneRequest;

public interface AuthService {

    LoginResponse login(LoginRequest request);

    /**
     * §2.2 刷新 Token。refreshToken 一次性，返回新值。
     *
     * @throws com.dsc.medipartner.common.exception.BizException
     *         refreshToken 无效/过期/已吊销（{@code 1004}）、账号禁用（{@code 2005}）
     */
    RefreshTokenResponse refresh(RefreshTokenRequest request);

    /**
     * §2.3 退出登录：accessToken 进黑名单（TTL = 剩余有效期）并吊销 refreshToken。
     *
     * @param accessToken 当前请求携带的 token（用于计算剩余有效期）
     */
    void logout(Long userId, String accessToken);

    /** §2.4 微信手机号快捷绑定，返回脱敏手机号 */
    String bindWechatPhone(Long userId, WechatPhoneRequest request);

    /** §2.6 短信验证码绑定手机号，返回脱敏手机号 */
    String bindPhoneBySms(Long userId, BindPhoneRequest request);
}
