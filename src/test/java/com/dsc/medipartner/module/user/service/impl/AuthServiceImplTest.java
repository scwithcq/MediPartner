package com.dsc.medipartner.module.user.service.impl;

import com.dsc.medipartner.common.cache.LocalCacheService;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.security.JwtUtil;
import com.dsc.medipartner.common.security.TokenService;
import com.dsc.medipartner.module.user.domain.dto.BindPhoneRequest;
import com.dsc.medipartner.module.user.domain.dto.LoginRequest;
import com.dsc.medipartner.module.user.domain.dto.LoginResponse;
import com.dsc.medipartner.module.user.domain.dto.RefreshTokenRequest;
import com.dsc.medipartner.module.user.domain.dto.RefreshTokenResponse;
import com.dsc.medipartner.module.user.domain.dto.WechatPhoneRequest;
import com.dsc.medipartner.module.user.domain.entity.SysUser;
import com.dsc.medipartner.module.user.domain.enums.RoleEnum;
import com.dsc.medipartner.module.user.domain.vo.UserVO;
import com.dsc.medipartner.module.user.service.SmsService;
import com.dsc.medipartner.module.user.service.UserService;
import com.dsc.medipartner.module.user.service.WechatAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认证服务测试（阶段 5，契约 §2.1~§2.6）。
 *
 * <p>TokenService 用真实实例（依赖 LocalCacheService），因为「登录发 token → 刷新换新 token →
 * 登出拉黑」是跨方法的状态流转；用户/SMS/微信边界用 mock，因为那属于外部依赖。
 */
class AuthServiceImplTest {

    private static final String SECRET = "Test_Secret_Key_For_Auth_Service_Unit_Test_0123456789";

    private UserService userService;
    private SmsService smsService;
    private WechatAuthService wechatAuthService;
    private TokenService tokenService;
    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        smsService = mock(SmsService.class);
        wechatAuthService = mock(WechatAuthService.class);
        tokenService = new TokenService(new LocalCacheService(), new JwtUtil(SECRET, 120), 10080);

        authService = new AuthServiceImpl(wechatAuthService, userService, smsService, tokenService);
        ReflectionTestUtils.setField(authService, "accessExpireMinutes", 120L);
    }

    private SysUser user(Long id, String phone, int status, int roleMask) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setPhone(phone);
        u.setStatus(status);
        u.setRoleMask(roleMask);
        u.setNickname("测试用户");
        return u;
    }

    private UserVO userVO(Long id) {
        UserVO vo = new UserVO();
        vo.setId(id);
        vo.setNickname("测试用户");
        vo.setRoleMask(RoleEnum.USER.getCode());
        return vo;
    }

    // ---------------- §2.1 登录 ----------------

    @Test
    void loginIssuesAccessAndRefreshTokens() {
        when(wechatAuthService.resolveOpenid("user1")).thenReturn("mock_openid_user1");
        when(userService.getByOpenid("mock_openid_user1")).thenReturn(user(1L, "13800001234", 1, 1));
        when(userService.getProfile(1L)).thenReturn(userVO(1L));

        LoginRequest request = new LoginRequest();
        request.setCode("user1");
        LoginResponse response = authService.login(request);

        assertThat(response.getToken()).isNotBlank();
        assertThat(response.getRefreshToken()).isNotBlank();
        assertThat(response.getExpiresIn()).isEqualTo(7200L);
        assertThat(response.getUserInfo()).isNotNull();
        // 已绑手机号 → 不需要引导绑定
        assertThat(response.getNeedBindPhone()).isFalse();
    }

    /** 未绑手机号时 needBindPhone=true，前端据此引导到绑定页（§2.1 阶段 5 起为真实值） */
    @Test
    void loginFlagsPhoneBindingWhenAbsent() {
        when(wechatAuthService.resolveOpenid("new")).thenReturn("mock_openid_new");
        when(userService.getByOpenid("mock_openid_new")).thenReturn(user(2L, null, 1, 1));
        when(userService.getProfile(2L)).thenReturn(userVO(2L));

        LoginRequest request = new LoginRequest();
        request.setCode("new");
        LoginResponse response = authService.login(request);

        assertThat(response.getNeedBindPhone()).isTrue();
    }

    /** 账号被禁用时拒绝登录（附录 B.2 的 2005） */
    @Test
    void disabledUserCannotLogin() {
        when(wechatAuthService.resolveOpenid("banned")).thenReturn("mock_openid_banned");
        when(userService.getByOpenid("mock_openid_banned")).thenReturn(user(3L, "13800001234", 0, 1));

        LoginRequest request = new LoginRequest();
        request.setCode("banned");

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_DISABLED);
    }

    /** 首次登录自动注册（§2.1 备注 2） */
    @Test
    void firstLoginCreatesUser() {
        when(wechatAuthService.resolveOpenid("brand-new")).thenReturn("mock_openid_brand-new");
        when(userService.getByOpenid("mock_openid_brand-new")).thenReturn(null);
        when(userService.createUser(eq("mock_openid_brand-new"), anyString(), eq(RoleEnum.USER.getCode())))
                .thenReturn(user(9L, null, 1, 1));
        when(userService.getProfile(9L)).thenReturn(userVO(9L));

        LoginRequest request = new LoginRequest();
        request.setCode("brand-new");
        LoginResponse response = authService.login(request);

        assertThat(response.getToken()).isNotBlank();
        verify(userService).createUser(eq("mock_openid_brand-new"), anyString(), eq(RoleEnum.USER.getCode()));
    }

    /** 登录下发的 refreshToken 必须真的能用来刷新（防止签发与校验口径不一致） */
    @Test
    void refreshTokenFromLoginActuallyWorks() {
        when(wechatAuthService.resolveOpenid("user1")).thenReturn("mock_openid_user1");
        when(userService.getByOpenid("mock_openid_user1")).thenReturn(user(1L, "13800001234", 1, 1));
        when(userService.getProfile(1L)).thenReturn(userVO(1L));
        when(userService.getById(1L)).thenReturn(user(1L, "13800001234", 1, 1));

        LoginRequest login = new LoginRequest();
        login.setCode("user1");
        String refreshToken = authService.login(login).getRefreshToken();

        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken(refreshToken);
        RefreshTokenResponse refreshed = authService.refresh(req);

        assertThat(refreshed.getToken()).isNotBlank();
        assertThat(refreshed.getRefreshToken()).isNotBlank();
        // 一次性轮换：新值与旧值必须不同
        assertThat(refreshed.getRefreshToken()).isNotEqualTo(refreshToken);
    }

    // ---------------- §2.2 刷新 ----------------

    @Test
    void blankRefreshTokenReturns1004() {
        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken("");

        assertThatThrownBy(() -> authService.refresh(req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void unknownRefreshTokenReturns1004() {
        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken("not-a-real-refresh-token");

        assertThatThrownBy(() -> authService.refresh(req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    /** 同一个 refreshToken 不能刷新两次（防重放） */
    @Test
    void refreshTokenCannotBeReused() {
        String raw = tokenService.issueRefreshToken(1L);
        when(userService.getById(1L)).thenReturn(user(1L, "13800001234", 1, 1));

        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken(raw);
        authService.refresh(req);

        assertThatThrownBy(() -> authService.refresh(req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    /** 账号被停用后即使 refreshToken 有效也不得续期，且凭据被吊销 */
    @Test
    void disabledUserCannotRefresh() {
        String raw = tokenService.issueRefreshToken(1L);
        when(userService.getById(1L)).thenReturn(user(1L, "13800001234", 0, 1));

        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken(raw);

        assertThatThrownBy(() -> authService.refresh(req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_DISABLED);

        // 凭据必须已被吊销，不能只是报错而已
        assertThat(tokenService.resolveUserIdByRefreshToken(raw)).isNull();
    }

    // ---------------- §2.3 登出 ----------------

    @Test
    void logoutBlacklistsAccessTokenAndRevokesRefresh() {
        String access = tokenService.issueAccessToken(1L, java.util.Set.of("ROLE_USER"));
        String refresh = tokenService.issueRefreshToken(1L);

        authService.logout(1L, access);

        // accessToken 进黑名单：请求过滤器会据此拒绝后续请求
        assertThat(tokenService.isBlacklisted(access)).isTrue();
        // refreshToken 被吊销：不能再用旧凭据换新 accessToken
        assertThat(tokenService.resolveUserIdByRefreshToken(refresh)).isNull();
    }

    // ---------------- §2.4 微信手机号绑定 ----------------

    @Test
    void wechatBindDelegatesToUserService() {
        WechatPhoneRequest req = new WechatPhoneRequest();
        req.setPhoneCode("13800001234");
        when(wechatAuthService.resolvePhone("13800001234")).thenReturn("13800001234");
        when(userService.bindPhone(1L, "13800001234")).thenReturn("138****1234");

        assertThat(authService.bindWechatPhone(1L, req)).isEqualTo("138****1234");
    }

    // ---------------- §2.6 短信绑定 ----------------

    @Test
    void smsBindVerifiesThenBinds() {
        BindPhoneRequest req = new BindPhoneRequest();
        req.setPhone("13800001234");
        req.setSmsCode("123456");
        when(userService.bindPhone(1L, "13800001234")).thenReturn("138****1234");

        assertThat(authService.bindPhoneBySms(1L, req)).isEqualTo("138****1234");
        verify(smsService).verifyCode("BIND_PHONE", "13800001234", "123456");
    }

    @Test
    void smsBindRejectsInvalidPhoneFormat() {
        BindPhoneRequest req = new BindPhoneRequest();
        req.setPhone("12345");
        req.setSmsCode("123456");

        assertThatThrownBy(() -> authService.bindPhoneBySms(1L, req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_ERROR);
        // 格式非法时不该打扰 SMS 服务
        verify(smsService, never()).verifyCode(anyString(), anyString(), anyString());
    }

    @Test
    void smsBindRequiresSmsCode() {
        BindPhoneRequest req = new BindPhoneRequest();
        req.setPhone("13800001234");
        req.setSmsCode("");

        assertThatThrownBy(() -> authService.bindPhoneBySms(1L, req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SMS_CODE_INVALID);
    }

    /** 手机号带空白时应归一化后再校验与绑定 */
    @Test
    void smsBindNormalizesPhone() {
        BindPhoneRequest req = new BindPhoneRequest();
        req.setPhone(" 138 0000 1234 ");
        req.setSmsCode("123456");
        when(userService.bindPhone(1L, "13800001234")).thenReturn("138****1234");

        authService.bindPhoneBySms(1L, req);

        verify(smsService).verifyCode("BIND_PHONE", "13800001234", "123456");
        verify(userService).bindPhone(1L, "13800001234");
    }

    /**
     * 验证码必须先于「手机号是否被占用」的判断。
     *
     * <p>若顺序反了，未持有验证码的攻击者就能用本接口逐一试探手机号，
     * 通过响应差异枚举出「哪些号码已注册」——这是典型的用户信息泄露。
     */
    @Test
    void verificationHappensBeforeAnyBindingAttempt() {
        BindPhoneRequest req = new BindPhoneRequest();
        req.setPhone("13800001234");
        req.setSmsCode("000000");
        org.mockito.Mockito.doThrow(new BizException(ErrorCode.SMS_CODE_INVALID))
                .when(smsService).verifyCode(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> authService.bindPhoneBySms(1L, req))
                .isInstanceOf(BizException.class);

        // 验证码没过，绝不能触碰绑定逻辑
        verify(userService, never()).bindPhone(anyLong(), anyString());
    }
}
