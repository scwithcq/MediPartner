package com.dsc.medipartner.common.ratelimit;

import com.dsc.medipartner.common.cache.LocalCacheService;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.security.LoginUser;
import com.dsc.medipartner.module.user.domain.dto.SmsCodeRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 限流切面测试（阶段 5，契约 §2.5 / §2.6 / §3.8）。
 *
 * <p>用真实 {@link LocalCacheService}：限流的核心是「窗口内计数」这一跨调用的状态，
 * mock 掉存储等于只验证了「方法被调用」。
 */
class RateLimitAspectTest {

    private LocalCacheService cache;
    private RateLimitAspect aspect;

    @BeforeEach
    void setUp() {
        cache = new LocalCacheService();
        aspect = new RateLimitAspect(cache);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private void login(long userId) {
        LoginUser user = new LoginUser(userId, Set.of("ROLE_USER"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.authorities()));
    }

    private void withRequest(String ip, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /** 构造一个 @RateLimit 注解实例（注解不能直接 new，用动态代理） */
    private RateLimit annotation(String name, int limit, int window, RateLimit.Dimension dimension) {
        return new RateLimit() {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return RateLimit.class;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public int limit() {
                return limit;
            }

            @Override
            public int windowSeconds() {
                return window;
            }

            @Override
            public Dimension dimension() {
                return dimension;
            }
        };
    }

    private ProceedingJoinPoint joinPoint(AtomicInteger calls, Object... args) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = RateLimitAspectTest.class.getDeclaredMethod("dummy", SmsCodeRequest.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getDeclaringType()).thenReturn(RateLimitAspectTest.class);
        when(signature.getName()).thenReturn("dummy");
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(args);
        when(pjp.proceed()).thenAnswer(inv -> {
            calls.incrementAndGet();
            return "ok";
        });
        return pjp;
    }

    @SuppressWarnings("unused")
    private void dummy(SmsCodeRequest request) {
        // 仅用于提供方法签名
    }

    // ---------------- 按用户限流 ----------------

    @Test
    void allowsRequestsWithinLimit() throws Throwable {
        login(1001L);
        RateLimit rl = annotation("test:user", 3, 60, RateLimit.Dimension.USER);
        AtomicInteger calls = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            aspect.around(joinPoint(calls), rl);
        }

        assertThat(calls.get()).isEqualTo(3);
    }

    /** 超出配额抛 1005（契约附录 B.2） */
    @Test
    void rejectsWhenLimitExceeded() throws Throwable {
        login(1001L);
        RateLimit rl = annotation("test:user2", 2, 60, RateLimit.Dimension.USER);
        AtomicInteger calls = new AtomicInteger();

        aspect.around(joinPoint(calls), rl);
        aspect.around(joinPoint(calls), rl);

        assertThatThrownBy(() -> aspect.around(joinPoint(calls), rl))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPEAT_REQUEST);

        // 被限流的请求绝不能执行业务逻辑
        assertThat(calls.get()).isEqualTo(2);
    }

    /** 不同用户配额独立：一个用户刷满不影响别人 */
    @Test
    void limitsAreIsolatedPerUser() throws Throwable {
        RateLimit rl = annotation("test:iso", 1, 60, RateLimit.Dimension.USER);
        AtomicInteger calls = new AtomicInteger();

        login(1001L);
        aspect.around(joinPoint(calls), rl);

        // 换用户：应当仍可通行
        login(2002L);
        aspect.around(joinPoint(calls), rl);

        assertThat(calls.get()).isEqualTo(2);
    }

    /** 不同接口名配额独立 */
    @Test
    void limitsAreIsolatedPerName() throws Throwable {
        login(1001L);
        AtomicInteger calls = new AtomicInteger();

        aspect.around(joinPoint(calls), annotation("api:a", 1, 60, RateLimit.Dimension.USER));
        aspect.around(joinPoint(calls), annotation("api:b", 1, 60, RateLimit.Dimension.USER));

        assertThat(calls.get()).isEqualTo(2);
    }

    /**
     * 窗口过期后恢复可用。
     *
     * <p>用真实等待验证窗口语义，而不是把 TTL 传 0——缓存层把 TTL 0 视为「永不过期」，
     * 那样测出来的是相反的行为（永久限流）。
     */
    @Test
    void recoversAfterWindowExpires() throws Throwable {
        login(1001L);
        // 1 秒窗口：先打满，等待过期后应重新可用
        RateLimit rl = annotation("test:windowExpire", 1, 1, RateLimit.Dimension.USER);
        AtomicInteger calls = new AtomicInteger();

        aspect.around(joinPoint(calls), rl);
        assertThatThrownBy(() -> aspect.around(joinPoint(calls), rl))
                .isInstanceOf(BizException.class);

        Thread.sleep(1200);

        // 窗口已过 → 恢复
        aspect.around(joinPoint(calls), rl);
        assertThat(calls.get()).isEqualTo(2);
    }

    // ---------------- 按手机号限流（§2.5 的关键） ----------------

    /**
     * 手机号维度：<b>换 IP 也必须被限住</b>。
     *
     * <p>这是 §2.5 的核心诉求——限流对象是「这个号码被骚扰的次数」。
     * 若按 IP 限，攻击者换个出口 IP 就能继续对同一号码发短信（短信资损）。
     */
    @Test
    void phoneDimensionLimitsAcrossDifferentIps() throws Throwable {
        RateLimit rl = annotation("sms:code", 1, 3600, RateLimit.Dimension.PHONE);
        AtomicInteger calls = new AtomicInteger();

        SmsCodeRequest request = new SmsCodeRequest();
        request.setPhone("13800001234");

        withRequest("1.1.1.1", null);
        aspect.around(joinPoint(calls, request), rl);

        // 换 IP，同一手机号 → 仍应被拦
        withRequest("2.2.2.2", null);
        assertThatThrownBy(() -> aspect.around(joinPoint(calls, request), rl))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPEAT_REQUEST);
    }

    /** 不同手机号互不影响 */
    @Test
    void differentPhonesHaveIndependentQuota() throws Throwable {
        RateLimit rl = annotation("sms:code2", 1, 3600, RateLimit.Dimension.PHONE);
        AtomicInteger calls = new AtomicInteger();

        SmsCodeRequest first = new SmsCodeRequest();
        first.setPhone("13800001234");
        SmsCodeRequest second = new SmsCodeRequest();
        second.setPhone("13900005678");

        withRequest("1.1.1.1", null);
        aspect.around(joinPoint(calls, first), rl);
        aspect.around(joinPoint(calls, second), rl);

        assertThat(calls.get()).isEqualTo(2);
    }

    /** 手机号带空白应归一化，避免用 "138 0000 1234" 绕过限流 */
    @Test
    void phoneIsNormalizedBeforeCounting() throws Throwable {
        RateLimit rl = annotation("sms:code3", 1, 3600, RateLimit.Dimension.PHONE);
        AtomicInteger calls = new AtomicInteger();

        SmsCodeRequest plain = new SmsCodeRequest();
        plain.setPhone("13800001234");
        SmsCodeRequest spaced = new SmsCodeRequest();
        spaced.setPhone(" 138 0000 1234 ");

        withRequest("1.1.1.1", null);
        aspect.around(joinPoint(calls, plain), rl);

        // 归一化后是同一号码 → 必须被拦
        assertThatThrownBy(() -> aspect.around(joinPoint(calls, spaced), rl))
                .isInstanceOf(BizException.class);
    }

    /** 取不到手机号时回落 IP，仍然提供保护（不因取不到而完全放行） */
    @Test
    void fallsBackToIpWhenPhoneMissing() throws Throwable {
        RateLimit rl = annotation("sms:fallback", 1, 3600, RateLimit.Dimension.PHONE);
        AtomicInteger calls = new AtomicInteger();

        SmsCodeRequest noPhone = new SmsCodeRequest();

        withRequest("3.3.3.3", null);
        aspect.around(joinPoint(calls, noPhone), rl);

        assertThatThrownBy(() -> aspect.around(joinPoint(calls, noPhone), rl))
                .isInstanceOf(BizException.class);
    }

    // ---------------- IP 维度 ----------------

    @Test
    void ipDimensionUsesForwardedForFirstHop() throws Throwable {
        RateLimit rl = annotation("ip:test", 1, 3600, RateLimit.Dimension.IP);
        AtomicInteger calls = new AtomicInteger();

        // XFF 可能是链式，取第一段（最接近客户端的真实来源）
        withRequest("10.0.0.1", "9.9.9.9, 10.0.0.5");
        aspect.around(joinPoint(calls), rl);

        withRequest("10.0.0.2", "9.9.9.9, 10.0.0.6");
        assertThatThrownBy(() -> aspect.around(joinPoint(calls), rl))
                .isInstanceOf(BizException.class);
    }

    @Test
    void ipDimensionFallsBackToRemoteAddr() throws Throwable {
        RateLimit rl = annotation("ip:remote", 1, 3600, RateLimit.Dimension.IP);
        AtomicInteger calls = new AtomicInteger();

        withRequest("4.4.4.4", null);
        aspect.around(joinPoint(calls), rl);

        assertThatThrownBy(() -> aspect.around(joinPoint(calls), rl))
                .isInstanceOf(BizException.class);
    }

    // ---------------- 降级与放行 ----------------

    /**
     * 无请求上下文（如定时任务内调用）且未登录时<b>放行</b>。
     *
     * <p>宁可漏限，也不能因为取不到限流标识而拒绝正常请求——
     * 那会把一个保护性能力变成故障源。
     */
    @Test
    void passesThroughWhenSubjectCannotBeResolved() throws Throwable {
        RequestContextHolder.resetRequestAttributes();
        RateLimit rl = annotation("test:nosubject", 1, 60, RateLimit.Dimension.USER);
        AtomicInteger calls = new AtomicInteger();

        // 未登录 + 无请求上下文 → 取不到标识
        aspect.around(joinPoint(calls), rl);
        aspect.around(joinPoint(calls), rl);

        // 两次都应执行（未限流）
        assertThat(calls.get()).isEqualTo(2);
    }

    /** 匿名接口按 IP 限流：多个匿名请求共享同一 IP 配额 */
    @Test
    void anonymousUserFallsBackToIp() throws Throwable {
        RateLimit rl = annotation("test:anon", 1, 3600, RateLimit.Dimension.USER);
        AtomicInteger calls = new AtomicInteger();

        withRequest("5.5.5.5", null);
        aspect.around(joinPoint(calls), rl);

        assertThatThrownBy(() -> aspect.around(joinPoint(calls), rl))
                .isInstanceOf(BizException.class);
    }
}
