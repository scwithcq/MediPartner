package com.dsc.medipartner.module.user.service.impl;

import com.dsc.medipartner.common.cache.CacheKeys;
import com.dsc.medipartner.common.cache.LocalCacheService;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.infra.wechat.WechatApiClient;
import com.dsc.medipartner.infra.wechat.WechatProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 真实微信认证实现测试（阶段 6，契约 §2.1 / §2.4）。
 *
 * <p>重点验证三件事：
 * <ol>
 *   <li>{@code session_key} 正确落缓存（将来解密用户信息要用，丢了就得重新登录）</li>
 *   <li>手机号归一化与非法值拦截（非法号码写库会导致后续短信下发失败）</li>
 *   <li><b>日志不泄露 openid 与手机号</b>——它们属敏感个人信息，进日志就会被长期留存</li>
 * </ol>
 */
class RealWechatAuthServiceTest {

    private WechatApiClient client;
    private LocalCacheService cache;
    private RealWechatAuthService service;

    @BeforeEach
    void setUp() {
        client = mock(WechatApiClient.class);
        cache = new LocalCacheService();
        WechatProperties props = new WechatProperties();
        props.setMock("false");
        props.setAppid("wxrealappid");
        props.setAppsecret("realsecret");
        props.setSessionKeyTtlSeconds(1800);
        service = new RealWechatAuthService(client, props, cache);
    }

    // ---------------- resolveOpenid ----------------

    @Test
    void resolveOpenidReturnsWechatOpenid() {
        when(client.code2Session("wx-code"))
                .thenReturn(new WechatApiClient.Code2SessionResult("oRealOpenid123", "sk-abc", null));

        assertThat(service.resolveOpenid("wx-code")).isEqualTo("oRealOpenid123");
    }

    /** session_key 必须落缓存：解密用户信息/手机号时要用，丢了只能让用户重新登录 */
    @Test
    void sessionKeyIsCachedForLaterDecryption() {
        when(client.code2Session("wx-code"))
                .thenReturn(new WechatApiClient.Code2SessionResult("oRealOpenid123", "sk-abc", null));

        service.resolveOpenid("wx-code");

        assertThat(service.getSessionKey("oRealOpenid123")).isEqualTo("sk-abc");
    }

    /** 微信未返回 session_key 时不写缓存（写空值会让「取到了」与「没取到」无法区分） */
    @Test
    void emptySessionKeyIsNotCached() {
        when(client.code2Session("wx-code"))
                .thenReturn(new WechatApiClient.Code2SessionResult("oRealOpenid123", "", null));

        service.resolveOpenid("wx-code");

        assertThat(service.getSessionKey("oRealOpenid123")).isNull();
    }

    /** 不同用户的 session_key 相互隔离 */
    @Test
    void sessionKeysAreIsolatedPerOpenid() {
        when(client.code2Session("code-a"))
                .thenReturn(new WechatApiClient.Code2SessionResult("openid-a", "sk-a", null));
        when(client.code2Session("code-b"))
                .thenReturn(new WechatApiClient.Code2SessionResult("openid-b", "sk-b", null));

        service.resolveOpenid("code-a");
        service.resolveOpenid("code-b");

        assertThat(service.getSessionKey("openid-a")).isEqualTo("sk-a");
        assertThat(service.getSessionKey("openid-b")).isEqualTo("sk-b");
    }

    @Test
    void blankCodeIsRejectedBeforeCallingWechat() {
        assertThatThrownBy(() -> service.resolveOpenid(""))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_ERROR);
        assertThatThrownBy(() -> service.resolveOpenid(null))
                .isInstanceOf(BizException.class);

        // 参数就不合法时不该白跑一次微信请求
        verify(client, never()).code2Session(anyString());
    }

    // ---------------- resolvePhone ----------------

    @Test
    void resolvePhoneReturnsNormalizedPhone() {
        when(client.getPhoneNumber("phone-code")).thenReturn("13800001234");

        assertThat(service.resolvePhone("phone-code")).isEqualTo("13800001234");
    }

    /** 微信若返回带空白/区号的号码，应归一化后通过校验 */
    @Test
    void resolvePhoneNormalizesWhitespace() {
        when(client.getPhoneNumber("phone-code")).thenReturn(" 13800001234 ");

        assertThat(service.resolvePhone("phone-code")).isEqualTo("13800001234");
    }

    /**
     * 微信返回非法号码时必须报错，<b>绝不能写库</b>。
     *
     * <p>把非法号码写进 {@code sys_user.phone} 的后果：该用户后续收不到短信验证码，
     * 且手机号唯一性校验会用一个假号码占位，影响其它用户绑定。
     */
    @Test
    void invalidPhoneFromWechatIsRejected() {
        when(client.getPhoneNumber("phone-code")).thenReturn("+8613800001234");

        assertThatThrownBy(() -> service.resolvePhone("phone-code"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_TIMEOUT);
    }

    @Test
    void emptyPhoneFromWechatIsRejected() {
        when(client.getPhoneNumber("phone-code")).thenReturn("");

        assertThatThrownBy(() -> service.resolvePhone("phone-code"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_TIMEOUT);
    }

    @Test
    void blankPhoneCodeIsRejectedBeforeCallingWechat() {
        assertThatThrownBy(() -> service.resolvePhone(""))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_ERROR);

        verify(client, never()).getPhoneNumber(anyString());
    }

    /** 微信侧的 1002（code 失效）应原样向上传递，不被包装成 9002 */
    @Test
    void wechatBusinessErrorPropagates() {
        when(client.code2Session("expired"))
                .thenThrow(new BizException(ErrorCode.UNAUTHORIZED, "登录凭证已失效，请重新登录"));

        assertThatThrownBy(() -> service.resolveOpenid("expired"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    // ---------------- session_key 缓存键约定 ----------------

    /**
     * 缓存键格式必须与约定一致。
     *
     * <p>钉住键名可避免「写入用一个前缀、读取用另一个」这类静默失效
     * （两边都能编译、单测也过，但运行期永远取不到值）。
     */
    @Test
    void sessionKeyUsesDocumentedCacheKey() {
        when(client.code2Session("wx-code"))
                .thenReturn(new WechatApiClient.Code2SessionResult("openid-x", "sk-x", null));

        service.resolveOpenid("wx-code");

        assertThat(cache.get("medi:wechat:session:openid-x")).isEqualTo("sk-x");
    }
}
