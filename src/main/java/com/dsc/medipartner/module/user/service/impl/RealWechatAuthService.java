package com.dsc.medipartner.module.user.service.impl;

import com.dsc.medipartner.common.cache.CacheService;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.util.PhoneUtil;
import com.dsc.medipartner.infra.wechat.WechatApiClient;
import com.dsc.medipartner.infra.wechat.WechatProperties;
import com.dsc.medipartner.module.user.service.WechatAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 真实微信认证实现（阶段 6，契约 §2.1 / §2.4）。
 *
 * <p>与 {@link MockWechatAuthService} 二选一装配，由 {@code medi.wechat.mock} 决定：
 * <ul>
 *   <li>{@code auto}（默认）：凭证齐备用本实现，否则用 mock</li>
 *   <li>{@code false}：强制本实现（缺凭证时首次调用明确报错）</li>
 *   <li>{@code true}：强制 mock</li>
 * </ul>
 *
 * <p><b>本类只替换「与微信交互的那一层」</b>，业务逻辑（建号、绑定手机号、唯一性校验、
 * token 签发）全部留在既有代码路径里——这是阶段 6 的核心纪律，
 * 否则会出现「联调通过、上线炸掉」：mock 路径绕过了真实业务规则，
 * 问题被推迟到生产才暴露。
 *
 * <p>{@code session_key} 落缓存而非数据库：它只在「解密用户信息/手机号」时短期需要，
 * 且属敏感密钥，入库会扩大泄露面。
 */
@Slf4j
@Service
@org.springframework.context.annotation.Conditional(
        com.dsc.medipartner.infra.wechat.WechatCondition.Real.class)
public class RealWechatAuthService implements WechatAuthService {

    /** session_key 缓存键前缀 */
    private static final String SESSION_KEY_PREFIX = "medi:wechat:session:";

    private final WechatApiClient client;
    private final WechatProperties props;
    private final CacheService cacheService;

    public RealWechatAuthService(WechatApiClient client, WechatProperties props,
                                 CacheService cacheService) {
        this.client = client;
        this.props = props;
        this.cacheService = cacheService;
        if (!props.credentialsConfigured()) {
            // 启动即告警：配置为强制真实却缺凭证，属部署错误，越早暴露越好
            log.error("[wechat] 已配置强制使用真实微信接口（medi.wechat.mock=false），"
                    + "但 AppID/AppSecret 未配置，登录与手机号绑定将在调用时报错");
        } else {
            log.info("[wechat] 已启用真实微信接口 appid={}", maskAppId(props.getAppid()));
        }
    }

    @Override
    public String resolveOpenid(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "code不能为空");
        }
        WechatApiClient.Code2SessionResult result = client.code2Session(code);
        // session_key 供后续解密使用（如解密用户信息、校验手机号归属）
        if (StringUtils.hasText(result.sessionKey())) {
            cacheService.set(SESSION_KEY_PREFIX + result.openid(), result.sessionKey(),
                    Duration.ofSeconds(props.getSessionKeyTtlSeconds()));
        }
        log.info("[wechat] code2Session 成功 openid={} 含sessionKey={} 含unionid={}",
                maskOpenid(result.openid()), StringUtils.hasText(result.sessionKey()),
                result.unionid() != null);
        return result.openid();
    }

    @Override
    public String resolvePhone(String phoneCode) {
        if (!StringUtils.hasText(phoneCode)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "phoneCode不能为空");
        }
        String rawPhone = client.getPhoneNumber(phoneCode);
        String phone = PhoneUtil.normalize(rawPhone);
        if (!PhoneUtil.isValid(phone)) {
            // 微信理论上只返回合法号码；走到这里说明响应格式变了，
            // 此时宁可报错也不能把非法值写进库（会导致后续短信下发失败）
            log.error("[wechat] getPhoneNumber 返回非法号码: {}", rawPhone);
            throw new BizException(ErrorCode.EXTERNAL_TIMEOUT, "获取手机号失败，请重试");
        }
        log.info("[wechat] 手机号解析成功 phone={}", maskPhone(phone));
        return phone;
    }

    /**
     * 取缓存的 session_key（供将来解密用户信息使用）。
     *
     * @return session_key；不存在或已过期返回 null
     */
    public String getSessionKey(String openid) {
        return cacheService.get(SESSION_KEY_PREFIX + openid);
    }

    // ---------------- 日志脱敏 ----------------
    //
    // ⚠ 日志里绝不能出现完整 openid 与手机号：
    // openid 是用户的微信身份标识（可跨本平台关联同一人），
    // 手机号更是敏感个人信息。二者一旦进日志，就会被日志采集系统长期留存。

    private String maskOpenid(String openid) {
        if (openid == null || openid.length() <= 8) {
            return "***";
        }
        return openid.substring(0, 4) + "****" + openid.substring(openid.length() - 4);
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    /** AppID 可部分展示（它本身不是密钥，但完整值也无需进日志） */
    private String maskAppId(String appid) {
        if (appid == null || appid.length() <= 6) {
            return "***";
        }
        return appid.substring(0, 6) + "****";
    }
}
