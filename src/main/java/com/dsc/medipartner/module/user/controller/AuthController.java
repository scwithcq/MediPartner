package com.dsc.medipartner.module.user.controller;

import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.module.user.domain.dto.BindPhoneRequest;
import com.dsc.medipartner.module.user.domain.dto.LoginRequest;
import com.dsc.medipartner.module.user.domain.dto.LoginResponse;
import com.dsc.medipartner.module.user.domain.dto.RefreshTokenRequest;
import com.dsc.medipartner.module.user.domain.dto.RefreshTokenResponse;
import com.dsc.medipartner.module.user.domain.dto.SmsCodeRequest;
import com.dsc.medipartner.module.user.domain.dto.WechatPhoneRequest;
import com.dsc.medipartner.module.user.domain.vo.SmsCodeVO;
import com.dsc.medipartner.module.user.service.AuthService;
import com.dsc.medipartner.module.user.service.SmsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证入口（契约 §2.1~§2.6）。
 *
 * <p><b>注意本类下所有路由都是 {@code permitAll}</b>（{@code SecurityConfig} 中
 * {@code /api/auth/**} 放行），因此除登录外的接口需<b>手动从 token 取 userId</b>。
 * refresh/logout 靠各自的凭据鉴权，绑定类接口靠 Bearer token
 * （{@code JwtAuthenticationFilter} 仍会解析并填充 SecurityContext）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SmsService smsService;

    public AuthController(AuthService authService, SmsService smsService) {
        this.authService = authService;
        this.smsService = smsService;
    }

    /** §2.1 微信一键登录（开发期为 mock：code 直接映射 openid） */
    @PostMapping("/wechat/login")
    public R<LoginResponse> wechatLogin(@Valid @RequestBody LoginRequest request) {
        return R.ok(authService.login(request));
    }

    /** §2.2 刷新 Token：匿名接口，凭 refreshToken 本身鉴权，一次性轮换 */
    @PostMapping("/refresh")
    public R<RefreshTokenResponse> refresh(@RequestBody RefreshTokenRequest request) {
        return R.ok(authService.refresh(request));
    }

    /**
     * §2.3 退出登录：accessToken 进黑名单 + 吊销 refreshToken。
     *
     * <p>需要从请求头取原始 token：黑名单的 TTL 要按该 token 的剩余有效期计算，
     * 而 {@code UserContext} 只提供 userId。
     */
    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest request) {
        authService.logout(UserContext.userId(), extractToken(request));
        return R.ok();
    }

    /** §2.4 微信手机号快捷绑定（开发期 mock：phoneCode 即手机号） */
    @PostMapping("/phone/wechat")
    public R<Map<String, String>> bindWechatPhone(@RequestBody WechatPhoneRequest request) {
        String phone = authService.bindWechatPhone(UserContext.userId(), request);
        return R.ok(Map.of("phone", phone));
    }

    /** §2.5 发送短信验证码：匿名，同手机号 60 秒一次（超限 1005） */
    @PostMapping("/sms/code")
    public R<SmsCodeVO> sendSmsCode(@RequestBody SmsCodeRequest request) {
        return R.ok(smsService.sendCode(request));
    }

    /** §2.6 短信验证码绑定手机号（§2.4 的兜底路径） */
    @PostMapping("/phone/bind")
    public R<Map<String, String>> bindPhone(@RequestBody BindPhoneRequest request) {
        String phone = authService.bindPhoneBySms(UserContext.userId(), request);
        return R.ok(Map.of("phone", phone));
    }

    /** 从 Authorization 头取出 Bearer token；缺失时返回 null（由 TokenService 按无 token 处理） */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
