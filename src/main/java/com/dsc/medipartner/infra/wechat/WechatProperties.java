package com.dsc.medipartner.infra.wechat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信开放能力配置（阶段 6，契约 §2.1 / §2.4 / §11 Q6 / Q10）。
 *
 * <p><b>密钥只从环境变量注入，严禁写进代码或配置文件明文</b>（与 AI Key 同一纪律）。
 * 未配置 {@code appid}/{@code appsecret} 时，框架自动回落到 mock 实现——
 * 保证没有凭证的开发环境仍能联调，而不是启动即失败。
 */
@Data
@Component
@ConfigurationProperties(prefix = "medi.wechat")
public class WechatProperties {

    /**
     * 开关模式：{@code auto}（默认）按凭证齐备情况自动选择；
     * {@code true} 强制 mock；{@code false} 强制真实实现。
     *
     * <p><b>为什么默认 auto 而不是布尔值</b>：本项目长期在无凭证环境开发，
     * 若默认布尔 {@code true}（强制 mock），则配好凭证后还得记得改开关，
     * 忘了改就会出现「以为在真实调用、实际全是假数据」——这是最危险的一类配置错误。
     * auto 模式让「有凭证就用真的」成为默认行为，无需人工同步两处配置。
     *
     * <p>生产建议显式写 {@code false}：这样缺凭证会在首次调用时明确报错，
     * 而不是悄悄降级。
     */
    private String mock = "auto";

    /**
     * 是否在 mock 模式下把 phoneCode 当作手机号（契约 §2.4）。
     *
     * <p>仅影响 {@code resolvePhone}：{@code true} 时传 11 位手机号即可完成绑定，
     * 便于在没有已认证小程序时联调。
     */
    private boolean mockPhone = true;

    /** 小程序 AppID（环境变量 {@code MEDI_WECHAT_APPID}） */
    private String appid = "";

    /** 小程序 AppSecret（环境变量 {@code MEDI_WECHAT_APPSECRET}） */
    private String appsecret = "";

    /** 微信接口基础地址，便于测试时指向本地桩服务 */
    private String baseUrl = "https://api.weixin.qq.com";

    /**
     * 微信接口调用超时（毫秒）。
     *
     * <p>取 5 秒：微信接口通常 100~500ms 返回，5 秒足以覆盖网络抖动；
     * 而登录是用户强感知的同步路径，等太久不如早失败早提示重试。
     */
    private int timeoutMs = 5000;

    /**
     * 失败重试次数（不含首次）。
     *
     * <p>只对<b>网络异常与 5xx</b>重试；微信返回的明确业务错误码（如 code 已使用）
     * 重试无意义，只会浪费用户等待时间。
     */
    private int maxRetries = 1;

    /**
     * session_key 缓存时长（秒），默认 30 分钟。
     *
     * <p>契约 §2.1 的登录只需 openid，session_key 是给「解密用户信息/手机号」用的。
     * 微信官方对 session_key 有效期无明确承诺（通常数天），
     * 取 30 分钟是保守值：过短会导致频繁重新登录，过长则在密钥轮换后解密失败。
     */
    private int sessionKeyTtlSeconds = 1800;

    /** 凭证是否齐备（缺任一即视为未配置） */
    public boolean credentialsConfigured() {
        return appid != null && !appid.isBlank()
                && appsecret != null && !appsecret.isBlank();
    }

    /**
     * 是否走 mock 实现。
     *
     * <p>判定顺序很重要：
     * <ol>
     *   <li>{@code mock=false} → <b>强制真实</b>，即便缺凭证也不降级。
     *       这样问题会在首次调用时立刻暴露（明确报「未配置 AppID」），
     *       而不是悄悄用假 openid 建出一批无法登录的账号。</li>
     *   <li>{@code mock=true} → 强制 mock，忽略已配置的凭证（供联调时临时回到假数据）。</li>
     *   <li>{@code mock=auto} → 凭证齐备用真实，否则 mock。</li>
     * </ol>
     */
    public boolean useMock() {
        if ("false".equalsIgnoreCase(mock)) {
            return false;
        }
        if ("true".equalsIgnoreCase(mock)) {
            return true;
        }
        return !credentialsConfigured();
    }

    /** 配置为强制真实但凭证缺失——启动与调用时都应给出明确提示 */
    public boolean misconfiguredStrictReal() {
        return "false".equalsIgnoreCase(mock) && !credentialsConfigured();
    }
}
