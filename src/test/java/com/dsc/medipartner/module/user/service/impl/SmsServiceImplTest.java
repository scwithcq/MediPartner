package com.dsc.medipartner.module.user.service.impl;

import com.dsc.medipartner.common.cache.LocalCacheService;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.user.domain.dto.SmsCodeRequest;
import com.dsc.medipartner.module.user.domain.vo.SmsCodeVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 短信验证码测试（阶段 5，契约 §2.5 / §2.6）。
 *
 * <p>用真实 {@link LocalCacheService}：验证码的「写入→过期→一次性消费→限流」
 * 全是跨调用的状态流转，mock 掉存储等于什么都没验证。
 */
class SmsServiceImplTest {

    private LocalCacheService cache;
    private SmsServiceImpl smsService;

    @BeforeEach
    void setUp() {
        cache = new LocalCacheService();
        smsService = new SmsServiceImpl(cache);
        ReflectionTestUtils.setField(smsService, "mock", true);
        ReflectionTestUtils.setField(smsService, "mockCode", "123456");
        ReflectionTestUtils.setField(smsService, "codeExpireSeconds", 300);
        ReflectionTestUtils.setField(smsService, "sendIntervalSeconds", 60);
    }

    private SmsCodeRequest request(String phone) {
        SmsCodeRequest req = new SmsCodeRequest();
        req.setPhone(phone);
        req.setScene("BIND_PHONE");
        return req;
    }

    // ---------------- 发送 ----------------

    @Test
    void sendCodeReturnsExpiryAndDebugCodeInMockMode() {
        SmsCodeVO vo = smsService.sendCode(request("13800001234"));

        assertThat(vo.getExpiresIn()).isEqualTo(300);
        assertThat(vo.getSendAt()).isNotNull();
        // 开发期回显验证码，便于本地联调
        assertThat(vo.getDebugCode()).isEqualTo("123456");
    }

    /**
     * 生产模式下绝不回显验证码。
     *
     * <p>这是一条安全红线：真实短信通道下把验证码放进响应，等于绕过短信直接完成绑定，
     * 整个验证码机制形同虚设。
     */
    @Test
    void debugCodeIsNullWhenMockDisabled() {
        ReflectionTestUtils.setField(smsService, "mock", false);

        SmsCodeVO vo = smsService.sendCode(request("13800001234"));

        assertThat(vo.getDebugCode()).isNull();
    }

    // ---------------- 手机号校验（契约要求 1001） ----------------

    @Test
    void invalidPhoneIsRejectedWith1001() {
        assertThatThrownBy(() -> smsService.sendCode(request("12345")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_ERROR);
    }

    @Test
    void blankPhoneIsRejected() {
        assertThatThrownBy(() -> smsService.sendCode(request("")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> smsService.sendCode(request(null)))
                .isInstanceOf(BizException.class);
    }

    /** 带空格/全角空格的手机号应被归一化后接受（用户粘贴常带空白） */
    @Test
    void phoneWithWhitespaceIsNormalized() {
        SmsCodeVO vo = smsService.sendCode(request(" 138 0000 1234 "));
        assertThat(vo.getDebugCode()).isEqualTo("123456");
        // 归一化后必须落在同一键上，否则验证时会取不到
        smsService.verifyCode("BIND_PHONE", "13800001234", "123456");
    }

    @Test
    void unsupportedSceneIsRejected() {
        SmsCodeRequest req = request("13800001234");
        req.setScene("SOMETHING_ELSE");
        assertThatThrownBy(() -> smsService.sendCode(req))
                .isInstanceOf(BizException.class);
    }

    // ---------------- 限流（契约 §2.5：同号 60s 一次 → 1005） ----------------

    @Test
    void secondSendWithinIntervalReturns1005() {
        smsService.sendCode(request("13800001234"));

        assertThatThrownBy(() -> smsService.sendCode(request("13800001234")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPEAT_REQUEST);
    }

    /** 限流按手机号隔离：给 A 发过不应影响给 B 发 */
    @Test
    void rateLimitIsPerPhone() {
        smsService.sendCode(request("13800001234"));
        smsService.sendCode(request("13900005678"));
    }

    /** 间隔窗口到期后应可再次发送 */
    @Test
    void canSendAgainAfterIntervalExpires() {
        ReflectionTestUtils.setField(smsService, "sendIntervalSeconds", 0);
        smsService.sendCode(request("13800001234"));
        smsService.sendCode(request("13800001234"));
    }

    // ---------------- 校验（契约 §2.6：一次性、错误码 2004） ----------------

    @Test
    void correctCodePassesVerification() {
        smsService.sendCode(request("13800001234"));
        smsService.verifyCode("BIND_PHONE", "13800001234", "123456");
    }

    @Test
    void wrongCodeReturns2004() {
        smsService.sendCode(request("13800001234"));

        assertThatThrownBy(() -> smsService.verifyCode("BIND_PHONE", "13800001234", "000000"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SMS_CODE_INVALID);
    }

    /**
     * 验证码一次性：用过后立即失效。
     *
     * <p>若允许多次使用，攻击者只需拿到一次验证码（例如通过社工或日志泄露），
     * 就能在 5 分钟窗口内反复绑定，限制形同虚设。
     */
    @Test
    void codeCannotBeUsedTwice() {
        smsService.sendCode(request("13800001234"));
        smsService.verifyCode("BIND_PHONE", "13800001234", "123456");

        assertThatThrownBy(() -> smsService.verifyCode("BIND_PHONE", "13800001234", "123456"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SMS_CODE_INVALID);
    }

    /** 未发送过验证码就校验，必须失败（不能默认通过） */
    @Test
    void verifyWithoutSendingFails() {
        assertThatThrownBy(() -> smsService.verifyCode("BIND_PHONE", "13800001234", "123456"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SMS_CODE_INVALID);
    }

    @Test
    void blankCodeReturns2004() {
        smsService.sendCode(request("13800001234"));
        assertThatThrownBy(() -> smsService.verifyCode("BIND_PHONE", "13800001234", ""))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SMS_CODE_INVALID);
        assertThatThrownBy(() -> smsService.verifyCode("BIND_PHONE", "13800001234", null))
                .isInstanceOf(BizException.class);
    }

    /** 场景隔离：绑定场景的验证码不能用于其它场景 */
    @Test
    void codeIsScopedToScene() {
        smsService.sendCode(request("13800001234"));

        assertThatThrownBy(() -> smsService.verifyCode("OTHER_SCENE", "13800001234", "123456"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SMS_CODE_INVALID);
    }

    /** 验证码错误不应消耗掉正确的验证码（否则用户输错一次就得重发） */
    @Test
    void wrongAttemptDoesNotConsumeValidCode() {
        smsService.sendCode(request("13800001234"));

        assertThatThrownBy(() -> smsService.verifyCode("BIND_PHONE", "13800001234", "000000"))
                .isInstanceOf(BizException.class);

        // 正确验证码仍然可用
        smsService.verifyCode("BIND_PHONE", "13800001234", "123456");
    }

    /** 校验时手机号同样归一化，避免发送与校验用了不同键 */
    @Test
    void verifyNormalizesPhoneToo() {
        smsService.sendCode(request("138 0000 1234"));
        smsService.verifyCode("BIND_PHONE", " 13800001234 ", "123456");
    }
}
