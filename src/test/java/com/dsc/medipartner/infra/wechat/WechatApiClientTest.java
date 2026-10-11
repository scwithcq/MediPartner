package com.dsc.medipartner.infra.wechat;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 微信接口客户端测试（阶段 6）。
 *
 * <p><b>用本地桩服务而非 Mockito</b>：本类要验证的正是「HTTP 收发 + JSON 解析 + 错误码映射」
 * 这条链路，mock 掉 RestClient 等于只测了自己写的分支，测不到
 * 「微信返回 200 但 errcode≠0」这类真实形态——那恰恰是接入微信最常见的坑。
 *
 * <p>桩服务用 JDK 自带的 {@code com.sun.net.httpserver}，不引入额外依赖。
 */
class WechatApiClientTest {

    private HttpServer server;
    private WechatApiClient client;
    private final AtomicInteger requestCount = new AtomicInteger();

    /** 桩服务返回的内容，每个用例自行设置 */
    private volatile String responseBody = "{}";
    private volatile int responseStatus = 200;
    private volatile String lastQuery = "";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            lastQuery = exchange.getRequestURI().getQuery();
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        WechatProperties props = new WechatProperties();
        props.setMock("false");
        props.setAppid("wxtestappid");
        props.setAppsecret("testsecret");
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setTimeoutMs(2000);
        props.setMaxRetries(1);
        client = new WechatApiClient(props, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ---------------- code2Session 成功路径 ----------------

    @Test
    void code2SessionParsesOpenidAndSessionKey() {
        responseBody = """
                {"openid":"oABC123xyz","session_key":"sk-abcdef","unionid":"u-999"}
                """;

        WechatApiClient.Code2SessionResult result = client.code2Session("js-code-1");

        assertThat(result.openid()).isEqualTo("oABC123xyz");
        assertThat(result.sessionKey()).isEqualTo("sk-abcdef");
        assertThat(result.unionid()).isEqualTo("u-999");
    }

    /** 无 unionid 时返回 null（未绑定开放平台的小程序不会有该字段） */
    @Test
    void code2SessionWithoutUnionidReturnsNull() {
        responseBody = """
                {"openid":"oABC123xyz","session_key":"sk-abcdef"}
                """;

        assertThat(client.code2Session("js-code-1").unionid()).isNull();
    }

    /** 成功响应里可能没有 errcode 字段，不能因此判为失败 */
    @Test
    void successfulResponseWithoutErrcodeFieldIsAccepted() {
        responseBody = """
                {"openid":"oABC123","session_key":"sk"}
                """;

        assertThat(client.code2Session("c").openid()).isEqualTo("oABC123");
    }

    /** 请求参数必须完整且正确（appid/secret/js_code/grant_type） */
    @Test
    void code2SessionSendsRequiredParams() {
        responseBody = """
                {"openid":"o1","session_key":"sk"}
                """;

        client.code2Session("my-code");

        assertThat(lastQuery).contains("appid=wxtestappid");
        assertThat(lastQuery).contains("secret=testsecret");
        assertThat(lastQuery).contains("js_code=my-code");
        assertThat(lastQuery).contains("grant_type=authorization_code");
    }

    /** 特殊字符必须 URL 编码，否则密钥含 +/& 时请求直接失败 */
    @Test
    void specialCharactersInParamsAreEncoded() {
        WechatProperties props = new WechatProperties();
        props.setMock("false");
        props.setAppid("wx+test&id");
        props.setAppsecret("sec/ret=1");
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setTimeoutMs(2000);
        WechatApiClient specialClient = new WechatApiClient(props, new ObjectMapper());
        responseBody = """
                {"openid":"o1","session_key":"sk"}
                """;

        specialClient.code2Session("code with space");

        // 未编码的 & 会把参数拆断、= 会改变参数值，必须编码为 %2B/%26/%2F/%3D
        assertThat(lastQuery).contains("appid=wx%2Btest%26id");
        assertThat(lastQuery).contains("secret=sec%2Fret%3D1");
        assertThat(lastQuery).contains("js_code=code+with+space");
    }

    // ---------------- code2Session 错误映射 ----------------

    /**
     * <b>40029（code 无效/已使用/过期）应映射为 1002（未登录），而不是 9002。</b>
     *
     * <p>这不是微信不可用，而是用户的 code 过期了：前端正确反应是重新 {@code wx.login()}。
     * 若笼统报「系统繁忙」，用户会反复点击并持续失败。
     */
    @Test
    void invalidCodeMapsTo1002Not9002() {
        responseBody = """
                {"errcode":40029,"errmsg":"invalid code"}
                """;

        assertThatThrownBy(() -> client.code2Session("expired"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    /** 40163（code 已被使用）同样映射 1002：并发重复提交同一 code */
    @Test
    void usedCodeMapsTo1002() {
        responseBody = """
                {"errcode":40163,"errmsg":"code been used"}
                """;

        assertThatThrownBy(() -> client.code2Session("used"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    /** 45011（频率限制）映射 1005，前端提示稍后重试 */
    @Test
    void frequencyLimitedMapsTo1005() {
        responseBody = """
                {"errcode":45011,"errmsg":"frequency limit"}
                """;

        assertThatThrownBy(() -> client.code2Session("code"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPEAT_REQUEST);
    }

    /** 其它错误码映射 9002（外部服务不可用） */
    @Test
    void otherErrorsMapTo9002() {
        responseBody = """
                {"errcode":-1,"errmsg":"system error"}
                """;

        assertThatThrownBy(() -> client.code2Session("code"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_TIMEOUT);
    }

    /**
     * 返回 errcode=0 但没有 openid，必须当失败。
     *
     * <p>否则会用一个空 openid 建出脏账号——这类账号永远无法再次登录
     * （因为每次互换都会得到不同的空值），属于不可修复的数据污染。
     */
    @Test
    void missingOpenidIsTreatedAsFailure() {
        responseBody = """
                {"errcode":0,"session_key":"sk"}
                """;

        assertThatThrownBy(() -> client.code2Session("code"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_TIMEOUT);
    }

    // ---------------- 网络层失败 ----------------

    /** 网络失败应重试；重试次数耗尽后抛 9002 */
    @Test
    void networkFailureIsRetriedThenThrows9002() {
        server.stop(0); // 关掉桩服务制造连接失败
        requestCount.set(0);

        assertThatThrownBy(() -> client.code2Session("code"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_TIMEOUT);
    }

    /** 业务错误不应重试（失效的 code 重试多少次都不会变有效） */
    @Test
    void businessErrorIsNotRetried() {
        responseBody = """
                {"errcode":40029,"errmsg":"invalid code"}
                """;
        requestCount.set(0);

        assertThatThrownBy(() -> client.code2Session("expired"))
                .isInstanceOf(BizException.class);

        assertThat(requestCount.get()).isEqualTo(1);
    }

    /** 空响应体视为失败 */
    @Test
    void emptyBodyIsTreatedAsFailure() {
        responseBody = "";

        assertThatThrownBy(() -> client.code2Session("code"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_TIMEOUT);
    }

    // ---------------- 手机号解析 ----------------

    /**
     * 手机号解析成功路径。
     *
     * <p>需要让桩服务对两次请求返回不同内容（先给 access_token，再给手机号），
     * 因此这里单独起一个「按路径分流」的桩，而不是复用固定响应体的那个。
     */
    @Test
    void getPhoneNumberParsesPurePhoneNumber() throws IOException {
        HttpServer routed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        routed.createContext("/cgi-bin/token", exchange -> {
            byte[] b = """
                    {"access_token":"tok-123","expires_in":7200}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, b.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(b);
            }
        });
        routed.createContext("/wxa/business/getuserphonenumber", exchange -> {
            byte[] b = """
                    {"errcode":0,"errmsg":"ok","phone_info":{"phoneNumber":"+8613800001234",
                     "purePhoneNumber":"13800001234","countryCode":"86"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, b.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(b);
            }
        });
        routed.start();

        try {
            WechatProperties props = new WechatProperties();
            props.setMock("false");
            props.setAppid("wxtest");
            props.setAppsecret("secret");
            props.setBaseUrl("http://127.0.0.1:" + routed.getAddress().getPort());
            props.setTimeoutMs(2000);
            WechatApiClient routedClient = new WechatApiClient(props, new ObjectMapper());

            // 必须取 purePhoneNumber（不带 +86 区号），否则无法通过 PhoneUtil 校验
            assertThat(routedClient.getPhoneNumber("phone-code")).isEqualTo("13800001234");
        } finally {
            routed.stop(0);
        }
    }

    /** 返回 errcode=0 但没有号码，必须当失败（否则会写入空手机号） */
    @Test
    void missingPhoneNumberIsTreatedAsFailure() throws IOException {
        HttpServer routed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        routed.createContext("/cgi-bin/token", exchange -> {
            byte[] b = """
                    {"access_token":"tok-123"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, b.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(b);
            }
        });
        routed.createContext("/wxa/business/getuserphonenumber", exchange -> {
            byte[] b = """
                    {"errcode":0,"phone_info":{}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, b.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(b);
            }
        });
        routed.start();

        try {
            WechatProperties props = new WechatProperties();
            props.setMock("false");
            props.setAppid("wxtest");
            props.setAppsecret("secret");
            props.setBaseUrl("http://127.0.0.1:" + routed.getAddress().getPort());
            props.setTimeoutMs(2000);
            WechatApiClient routedClient = new WechatApiClient(props, new ObjectMapper());

            assertThatThrownBy(() -> routedClient.getPhoneNumber("phone-code"))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXTERNAL_TIMEOUT);
        } finally {
            routed.stop(0);
        }
    }

    /** access_token 获取失败时抛 9002，且不会继续请求手机号接口 */
    @Test
    void tokenFailureStopsBeforePhoneRequest() {
        responseBody = """
                {"errcode":40125,"errmsg":"invalid appsecret"}
                """;
        requestCount.set(0);

        assertThatThrownBy(() -> client.getPhoneNumber("phone-code"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_TIMEOUT);

        // 只调了取 token 那一次
        assertThat(requestCount.get()).isEqualTo(1);
    }

    // ---------------- 凭证缺失 ----------------

    /**
     * 凭证缺失抛 9001（系统异常）而非 9002。
     *
     * <p>这是<b>部署配置错误</b>，不是微信的问题——重试不会有任何改善，
     * 运维需要从日志立刻看出是 AppID/AppSecret 没配。
     */
    @Test
    void missingCredentialsThrows9001() {
        WechatProperties noCreds = new WechatProperties();
        noCreds.setMock("false");
        noCreds.setAppid("");
        noCreds.setAppsecret("");
        noCreds.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        WechatApiClient bare = new WechatApiClient(noCreds, new ObjectMapper());

        assertThatThrownBy(() -> bare.code2Session("code"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SYSTEM_ERROR);

        // 未发起任何请求
        assertThat(requestCount.get()).isZero();
    }
}
