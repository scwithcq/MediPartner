package com.dsc.medipartner.infra.wechat;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信小程序服务端接口客户端（阶段 6）。
 *
 * <p>覆盖两项能力：
 * <ol>
 *   <li>{@code auth.code2Session} —— 用 {@code wx.login()} 的 code 换 openid / session_key（§2.1）</li>
 *   <li>{@code phonenumber.getPhoneNumber} —— 用 {@code getPhoneNumber} 回调的 code 换手机号（§2.4）</li>
 * </ol>
 *
 * <p><b>错误处理的两条原则</b>：
 * <ol>
 *   <li><b>网络层失败</b>（超时、连不上、5xx）→ 抛 {@code 9002 EXTERNAL_TIMEOUT}，
 *       前端提示「稍后重试」。只对这类失败重试，因为它是瞬时的。</li>
 *   <li><b>业务层失败</b>（微信返回 errcode ≠ 0）→ 转换成本项目的语义化错误。
 *       例如 {@code 40029 invalid code} 是<b>用户操作过期</b>（code 一次性、5 分钟有效），
 *       应提示重新登录而不是「系统繁忙」；盲目重试同一个失效 code 永远不可能成功。</li>
 * </ol>
 *
 * <p>微信的 HTTP 状态码恒为 200，错误信息在响应体的 {@code errcode}/{@code errmsg} 里，
 * 因此<b>不能靠状态码判断成败</b>——这是接入微信最常见的坑。
 */
@Slf4j
@Component
public class WechatApiClient {

    /** 微信业务错误码（官方文档 errcode） */
    private static final int ERR_OK = 0;
    /** code 无效/已使用/过期：属用户侧操作超时，应引导重新登录 */
    private static final int ERR_INVALID_CODE = 40029;
    /** code 已被使用（并发重复提交同一 code） */
    private static final int ERR_CODE_USED = 40163;
    /** 频率限制 */
    private static final int ERR_FREQUENCY_LIMITED = 45011;
    /** access_token 无效（给手机号接口用，本类暂不缓存 token，仅做错误识别） */
    private static final int ERR_INVALID_TOKEN = 40001;

    private final WechatProperties props;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public WechatApiClient(WechatProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getTimeoutMs());
        factory.setReadTimeout(props.getTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * {@code auth.code2Session}：code → openid / session_key。
     *
     * @param code {@code wx.login()} 返回的临时凭证，一次性、5 分钟有效
     * @return 包含 openid 与 sessionKey 的结果
     * @throws BizException 凭证未配置（{@code 9001}）、微信接口失败（{@code 9002}）、
     *                      code 无效或已使用（{@code 1002}，引导重新登录）
     */
    public Code2SessionResult code2Session(String code) {
        requireCredentials();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("appid", props.getAppid());
        params.put("secret", props.getAppsecret());
        params.put("js_code", code);
        params.put("grant_type", "authorization_code");

        JsonNode body = get("/sns/jscode2session", params, "code2Session");
        // 微信在成功时不返回 errcode 字段（而非返回 errcode=0），两者都要视为成功
        int errcode = body.path("errcode").asInt(ERR_OK);
        if (errcode != ERR_OK) {
            throw mapCode2SessionError(errcode, body.path("errmsg").asText(""));
        }

        String openid = body.path("openid").asText("");
        if (!StringUtils.hasText(openid)) {
            // 返回 200 且 errcode=0 却没有 openid：属微信侧异常响应，
            // 必须当成失败——否则会用一个空 openid 建出脏账号
            log.error("[wechat] code2Session 返回成功但缺少 openid，响应={}", body);
            throw new BizException(ErrorCode.EXTERNAL_TIMEOUT, "微信登录返回异常，请重试");
        }
        String sessionKey = body.path("session_key").asText("");
        String unionid = body.path("unionid").asText("");
        return new Code2SessionResult(openid, sessionKey, StringUtils.hasText(unionid) ? unionid : null);
    }

    /**
     * {@code phonenumber.getPhoneNumber}：phoneCode → 手机号。
     *
     * <p>需要 {@code access_token}。本类<b>不缓存 token</b>：获取 token 需额外一次请求，
     * 而手机号绑定是低频操作（用户一生可能就绑一次），
     * 为省一次请求引入 token 缓存与并发刷新的复杂度并不划算。
     * 若将来绑定频率上升，再加 {@code Redis} 缓存与提前刷新。
     */
    public String getPhoneNumber(String phoneCode) {
        requireCredentials();
        String accessToken = fetchAccessToken();

        Map<String, String> params = new LinkedHashMap<>();
        params.put("access_token", accessToken);
        JsonNode body = post("/wxa/business/getuserphonenumber",
                params, Map.of("code", phoneCode), "getPhoneNumber");

        int errcode = body.path("errcode").asInt(ERR_OK);
        if (errcode != ERR_OK) {
            throw mapPhoneError(errcode, body.path("errmsg").asText(""));
        }

        // 号码在 phone_info.purePhoneNumber（不带区号）里
        String phone = body.path("phone_info").path("purePhoneNumber").asText("");
        if (!StringUtils.hasText(phone)) {
            log.error("[wechat] getPhoneNumber 返回成功但缺少号码，响应={}", body);
            throw new BizException(ErrorCode.EXTERNAL_TIMEOUT, "微信手机号返回异常，请重试");
        }
        return phone;
    }

    /** 获取 access_token；失败抛 9002 */
    private String fetchAccessToken() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "client_credential");
        params.put("appid", props.getAppid());
        params.put("secret", props.getAppsecret());

        JsonNode body = get("/cgi-bin/token", params, "getAccessToken");
        String token = body.path("access_token").asText("");
        if (!StringUtils.hasText(token)) {
            int errcode = body.path("errcode").asInt(ERR_OK);
            log.error("[wechat] 获取 access_token 失败 errcode={} errmsg={}",
                    errcode, body.path("errmsg").asText(""));
            throw new BizException(ErrorCode.EXTERNAL_TIMEOUT, "微信服务暂不可用，请稍后重试");
        }
        return token;
    }

    // ---------------- 错误码映射 ----------------

    /**
     * code2Session 错误映射。
     *
     * <p>{@code 40029}/{@code 40163} 归为 {@code 1002}（未登录/登录已过期）而非 {@code 9002}：
     * 这<b>不是</b>微信不可用，而是用户的 code 过期或已被使用，
     * 前端正确反应是重新 {@code wx.login()}，重试同一个 code 永远不会成功。
     * 若笼统报「系统繁忙」，用户会反复点击并持续失败。
     */
    private BizException mapCode2SessionError(int errcode, String errmsg) {
        log.warn("[wechat] code2Session 失败 errcode={} errmsg={}", errcode, errmsg);
        return switch (errcode) {
            case ERR_INVALID_CODE, ERR_CODE_USED ->
                    new BizException(ErrorCode.UNAUTHORIZED, "登录凭证已失效，请重新登录");
            case ERR_FREQUENCY_LIMITED ->
                    new BizException(ErrorCode.REPEAT_REQUEST, "操作过于频繁，请稍后再试");
            default -> new BizException(ErrorCode.EXTERNAL_TIMEOUT, "微信登录失败，请稍后重试");
        };
    }

    private BizException mapPhoneError(int errcode, String errmsg) {
        log.warn("[wechat] getPhoneNumber 失败 errcode={} errmsg={}", errcode, errmsg);
        return switch (errcode) {
            // token 失效属服务端问题（本类每次现取，理论上不会遇到），仍提示重试
            case ERR_INVALID_TOKEN -> new BizException(ErrorCode.EXTERNAL_TIMEOUT, "微信服务暂不可用，请重试");
            case ERR_INVALID_CODE, ERR_CODE_USED ->
                    new BizException(ErrorCode.UNAUTHORIZED, "手机号授权已失效，请重新授权");
            case ERR_FREQUENCY_LIMITED ->
                    new BizException(ErrorCode.REPEAT_REQUEST, "操作过于频繁，请稍后再试");
            default -> new BizException(ErrorCode.EXTERNAL_TIMEOUT, "获取手机号失败，请稍后重试");
        };
    }

    // ---------------- HTTP 收发 ----------------

    private JsonNode get(String path, Map<String, String> params, String action) {
        String url = buildUrl(path, params);
        return execute(action, () -> restClient.get().uri(url).retrieve().body(String.class));
    }

    private JsonNode post(String path, Map<String, String> params, Object body, String action) {
        String url = buildUrl(path, params);
        return execute(action, () -> restClient.post().uri(url)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class));
    }

    /**
     * 统一执行：超时/网络异常重试一次，最终仍失败抛 {@code 9002}。
     *
     * <p>只重试网络层失败。业务错误码由各自方法处理，不在此重试
     * （失效的 code 重试多少次都不会变成有效）。
     */
    private JsonNode execute(String action, java.util.function.Supplier<String> call) {
        int attempts = Math.max(props.getMaxRetries(), 0) + 1;
        Exception last = null;
        for (int i = 1; i <= attempts; i++) {
            try {
                String raw = call.get();
                if (!StringUtils.hasText(raw)) {
                    throw new RestClientException("微信返回空响应体");
                }
                return objectMapper.readTree(raw);
            } catch (Exception e) {
                last = e;
                log.warn("[wechat] {} 第 {}/{} 次调用失败: {}", action, i, attempts, e.getMessage());
            }
        }
        // 走到这里说明重试耗尽，属外部服务不可用
        log.error("[wechat] {} 调用最终失败", action, last);
        throw new BizException(ErrorCode.EXTERNAL_TIMEOUT, "微信服务暂不可用，请稍后重试");
    }

    /** 拼接 URL 并做参数编码（AppSecret 含特殊字符时不编码会直接请求失败） */
    private String buildUrl(String path, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(props.getBaseUrl()).append(path).append('?');
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            sb.append(entry.getKey()).append('=')
                    .append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(),
                            StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    /**
     * 校验凭证已配置。
     *
     * <p>缺失时抛 {@code 9001}（系统异常）而非 {@code 9002}（外部超时）：
     * 这是<b>部署配置错误</b>，不是微信的问题，重试不会有任何改善，
     * 运维应立刻从日志中看到是 AppID/AppSecret 没配。
     */
    private void requireCredentials() {
        if (!props.credentialsConfigured()) {
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "微信 AppID/AppSecret 未配置，请在环境变量 MEDI_WECHAT_APPID / MEDI_WECHAT_APPSECRET 中设置");
        }
    }

    /** code2Session 结果 */
    public record Code2SessionResult(String openid, String sessionKey, String unionid) {
    }
}
