package com.dsc.medipartner.integration;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.user.service.WechatAuthService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阶段 6 真实微信链路的集成验证：用<b>本地桩服务</b>模拟微信接口。
 *
 * <p><b>为什么用桩而不是等真实凭证</b>：没有 AppID/AppSecret 时，
 * 「真实实现」这条分支永远得不到验证——它会在生产首次调用时才暴露问题。
 * 桩服务让整条链路（Spring 装配 → HTTP 客户端 → JSON 解析 → 错误映射 →
 * 认证服务 → 手机号归一化）在 CI 里就能真跑一遍。
 *
 * <p><b>桩不能替代的部分</b>（必须等真实凭证才能验）：
 * <ul>
 *   <li>真实 AppSecret 签名与权限（桩不校验）</li>
 *   <li>微信对「未备案域名/未认证小程序」的实际拦截行为</li>
 *   <li>真实 openid 的形态与长度</li>
 * </ul>
 *
 * <p>本测试通过 {@code @DynamicPropertySource} 把 {@code base-url} 指向桩服务，
 * 并显式设 {@code mock=false} 以强制走真实实现。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
@DisplayName("阶段6 真实微信链路（本地桩服务）")
class Stage6WechatStubIntegrationTest {

    private static HttpServer stub;
    private static volatile String code2SessionBody = "{}";
    private static volatile int code2SessionStatus = 200;
    private static volatile String phoneBody = "{}";

    @Autowired
    private WechatAuthService wechatAuthService;

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        stub.createContext("/sns/jscode2session", exchange -> respond(exchange, code2SessionBody, code2SessionStatus));
        stub.createContext("/cgi-bin/token", exchange -> respond(exchange,
                "{\"access_token\":\"stub-token\",\"expires_in\":7200}", 200));
        stub.createContext("/wxa/business/getuserphonenumber", exchange -> respond(exchange, phoneBody, 200));
        stub.start();
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body, int status)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 把微信接口地址指向桩服务，并强制使用真实实现 */
    @DynamicPropertySource
    static void wechatProperties(DynamicPropertyRegistry registry) {
        registry.add("medi.wechat.mock", () -> "false");
        registry.add("medi.wechat.appid", () -> "wxstubappid");
        registry.add("medi.wechat.appsecret", () -> "stubsecret");
        registry.add("medi.wechat.base-url",
                () -> "http://127.0.0.1:" + stub.getAddress().getPort());
    }

    @Test
    @DisplayName("真实实现已装配（非 mock）")
    void realImplementationIsWired() {
        assertThat(wechatAuthService.getClass().getSimpleName())
                .isEqualTo("RealWechatAuthService");
    }

    /** 完整成功链路：桩返回 openid → 认证服务原样返回 */
    @Test
    @DisplayName("code2Session 成功：返回桩返回的 openid")
    void code2SessionSuccess() {
        code2SessionBody = "{\"openid\":\"oStubUser001\",\"session_key\":\"stub-sk\"}";

        assertThat(wechatAuthService.resolveOpenid("valid-code")).isEqualTo("oStubUser001");
    }

    /** code 失效：桩返回 40029 → 映射为 1002（引导重新登录），不是 9002 */
    @Test
    @DisplayName("code 失效映射为 1002 而非 9002")
    void expiredCodeMapsTo1002() {
        code2SessionBody = "{\"errcode\":40029,\"errmsg\":\"invalid code\"}";

        assertThatThrownBy(() -> wechatAuthService.resolveOpenid("expired-code"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    /** 微信 5xx：映射为 9002（可重试的外部故障） */
    @Test
    @DisplayName("微信 5xx 映射为 9002")
    void serverErrorMapsTo9002() {
        code2SessionStatus = 500;
        code2SessionBody = "internal error";

        try {
            assertThatThrownBy(() -> wechatAuthService.resolveOpenid("any-code"))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXTERNAL_TIMEOUT);
        } finally {
            code2SessionStatus = 200;
        }
    }

    /** 手机号解析成功：取 purePhoneNumber 并通过格式校验 */
    @Test
    @DisplayName("手机号解析成功：取 purePhoneNumber")
    void phoneResolveSuccess() {
        phoneBody = "{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"13800009999\","
                + "\"phoneNumber\":\"+8613800009999\",\"countryCode\":\"86\"}}";

        assertThat(wechatAuthService.resolvePhone("phone-code")).isEqualTo("13800009999");
    }

    /** 手机号授权失效：桩返回 40029 → 1002 */
    @Test
    @DisplayName("手机号授权失效映射为 1002")
    void expiredPhoneCodeMapsTo1002() {
        phoneBody = "{\"errcode\":40029,\"errmsg\":\"invalid code\"}";

        assertThatThrownBy(() -> wechatAuthService.resolvePhone("expired"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    /** 手机号接口返回非法号码 → 抛 9002，不写库 */
    @Test
    @DisplayName("非法号码被拦截，不写库")
    void illegalPhoneIsRejected() {
        phoneBody = "{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"not-a-phone\"}}";

        assertThatThrownBy(() -> wechatAuthService.resolvePhone("phone-code"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_TIMEOUT);
    }
}
