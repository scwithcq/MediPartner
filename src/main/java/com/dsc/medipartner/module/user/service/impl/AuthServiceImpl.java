package com.dsc.medipartner.module.user.service.impl;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.security.TokenService;
import com.dsc.medipartner.common.util.PhoneUtil;
import com.dsc.medipartner.module.user.domain.dto.BindPhoneRequest;
import com.dsc.medipartner.module.user.domain.dto.LoginRequest;
import com.dsc.medipartner.module.user.domain.dto.LoginResponse;
import com.dsc.medipartner.module.user.domain.dto.RefreshTokenRequest;
import com.dsc.medipartner.module.user.domain.dto.RefreshTokenResponse;
import com.dsc.medipartner.module.user.domain.dto.WechatPhoneRequest;
import com.dsc.medipartner.module.user.domain.entity.SysUser;
import com.dsc.medipartner.module.user.domain.enums.RoleEnum;
import com.dsc.medipartner.module.user.domain.vo.UserVO;
import com.dsc.medipartner.module.user.service.AuthService;
import com.dsc.medipartner.module.user.service.SmsService;
import com.dsc.medipartner.module.user.service.UserService;
import com.dsc.medipartner.module.user.service.WechatAuthService;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 认证服务实现（契约 §2.1~§2.6）。
 *
 * <p><b>阶段 5 新增的三道安全约束</b>：
 * <ol>
 *   <li>refreshToken 一次性轮换，存 Redis 指纹（{@code TokenService}）</li>
 *   <li>登出把 accessToken 拉黑，JWT 不再「签发后无法撤销」</li>
 *   <li>绑定手机号前做全局唯一性校验，且<b>排除自己</b>（否则换绑回自己的号会被误判占用）</li>
 * </ol>
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    /** §2.5 短信绑定场景码，与 {@code SmsServiceImpl} 保持一致 */
    private static final String SCENE_BIND_PHONE = "BIND_PHONE";

    private final WechatAuthService wechatAuthService;
    private final UserService userService;
    private final SmsService smsService;
    private final TokenService tokenService;

    @Value("${medi.jwt.expire-minutes:120}")
    private long accessExpireMinutes;

    public AuthServiceImpl(WechatAuthService wechatAuthService, UserService userService,
                           SmsService smsService, TokenService tokenService) {
        this.wechatAuthService = wechatAuthService;
        this.userService = userService;
        this.smsService = smsService;
        this.tokenService = tokenService;
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        String openid = wechatAuthService.resolveOpenid(request.getCode());
        SysUser user = userService.getByOpenid(openid);
        if (user == null) {
            String nickname = StringUtils.hasText(request.getNickname())
                    ? request.getNickname()
                    : "用户" + openid.substring(openid.length() - 4);
            user = userService.createUser(openid, nickname, RoleEnum.USER.getCode());
        }
        // 附录 B.2：账号被禁用时不允许登录（阶段 7 管理端停用账号后生效）
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BizException(ErrorCode.USER_DISABLED);
        }

        LoginResponse response = new LoginResponse();
        response.setToken(tokenService.issueAccessToken(user.getId(), RoleEnum.authoritiesOf(user.getRoleMask())));
        // §2.1：含 roles 与脱敏手机号（阶段 1.5 起不再出裸实体）
        response.setUserInfo(userService.getProfile(user.getId()));
        response.setNeedBindPhone(!StringUtils.hasText(user.getPhone()));
        // §2.1 阶段 5：登录即下发 refreshToken，前端存本地用于静默续期
        response.setRefreshToken(tokenService.issueRefreshToken(user.getId()));
        response.setExpiresIn(accessExpireMinutes * 60);
        return response;
    }

    /**
     * §2.2 刷新 Token。
     *
     * <p>本接口是<b>匿名</b>的（凭 refreshToken 本身鉴权，契约 §2.2），
     * 请求体只有 token、没有 userId，因此先由 {@code TokenService} 按指纹反查身份，
     * 同时消费掉旧 token（一次性轮换）。
     */
    @Override
    public RefreshTokenResponse refresh(RefreshTokenRequest request) {
        String raw = request == null ? null : request.getRefreshToken();
        if (!StringUtils.hasText(raw)) {
            throw new BizException(ErrorCode.TOKEN_EXPIRED, "refreshToken 不能为空");
        }

        Long userId = tokenService.resolveUserIdByRefreshToken(raw);
        if (userId == null) {
            throw new BizException(ErrorCode.TOKEN_EXPIRED, "登录已过期，请重新登录");
        }

        SysUser user = userService.getById(userId);
        if (user.getStatus() != null && user.getStatus() == 0) {
            // 账号被停用后即使 refreshToken 未过期也不得续期
            tokenService.revokeRefreshToken(userId);
            throw new BizException(ErrorCode.USER_DISABLED);
        }

        RefreshTokenResponse response = new RefreshTokenResponse();
        response.setToken(tokenService.issueAccessToken(userId, RoleEnum.authoritiesOf(user.getRoleMask())));
        // 一次性轮换：旧值已被消费，返回新值，前端必须覆盖本地存储
        response.setRefreshToken(tokenService.issueRefreshToken(userId));
        response.setExpiresIn(tokenService.refreshExpireSeconds());
        return response;
    }

    @Override
    public void logout(Long userId, String accessToken) {
        // ① accessToken 进黑名单，剩余有效期内拒绝该 token（§2.3）
        tokenService.blacklist(accessToken);
        // ② 吊销 refreshToken，防止用旧凭据换新 accessToken
        tokenService.revokeRefreshToken(userId);
    }

    @Override
    public String bindWechatPhone(Long userId, WechatPhoneRequest request) {
        String phone = wechatAuthService.resolvePhone(request == null ? null : request.getPhoneCode());
        // 唯一性校验与写入统一走 UserService，避免两处各写一份判重逻辑而产生分歧
        return userService.bindPhone(userId, phone);
    }

    @Override
    public String bindPhoneBySms(Long userId, BindPhoneRequest request) {
        String phone = PhoneUtil.normalize(request == null ? null : request.getPhone());
        if (!PhoneUtil.isValid(phone)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "手机号格式不正确");
        }
        if (!StringUtils.hasText(request.getSmsCode())) {
            throw new BizException(ErrorCode.SMS_CODE_INVALID);
        }
        // 先校验验证码（一次性，校验成功即删除）。
        // 顺序很重要：若先判手机号是否被占用，攻击者就能用本接口
        // 枚举「哪些手机号已注册」，而不需要任何验证码。
        smsService.verifyCode(SCENE_BIND_PHONE, phone, request.getSmsCode());
        return userService.bindPhone(userId, phone);
    }
}
