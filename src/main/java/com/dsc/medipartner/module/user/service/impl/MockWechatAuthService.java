package com.dsc.medipartner.module.user.service.impl;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.util.PhoneUtil;
import com.dsc.medipartner.infra.wechat.WechatProperties;
import com.dsc.medipartner.module.user.service.WechatAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 开发阶段的模拟微信登录实现，直接把 code 映射为 openid。
 *
 * <p>装配条件见 {@link com.dsc.medipartner.infra.wechat.WechatCondition.Mock}：
 * {@code medi.wechat.mock=auto}（默认）时凭证缺失才启用本类；
 * 配好 AppID/AppSecret 后自动切换到 {@link RealWechatAuthService}，
 * <b>接口签名完全一致</b>，业务代码无感知。
 *
 * <p>⚠ mock 只替换「与微信交互的那一层」：建号、绑定、唯一性校验、token 签发
 * 全部走既有业务路径，因此 mock 下的联调结果对真实环境同样有意义。
 */
@Slf4j
@Service
@org.springframework.context.annotation.Conditional(
        com.dsc.medipartner.infra.wechat.WechatCondition.Mock.class)
public class MockWechatAuthService implements WechatAuthService {

    private final WechatProperties props;

    public MockWechatAuthService(WechatProperties props) {
        this.props = props;
        log.warn("[wechat] 当前使用【模拟】微信登录实现：code 直接映射为 mock_openid_<code>。"
                + "配置 MEDI_WECHAT_APPID / MEDI_WECHAT_APPSECRET 后自动切换为真实接口");
    }

    @Override
    public String resolveOpenid(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "code不能为空");
        }
        return "mock_openid_" + code;
    }

    @Override
    public String resolvePhone(String phoneCode) {
        if (!StringUtils.hasText(phoneCode)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "phoneCode不能为空");
        }
        if (props.isMockPhone()) {
            // 开发期：phoneCode 即手机号。仍做格式校验，
            // 避免把明显非法的值写进库（否则问题会推迟到发短信时才暴露）
            String phone = PhoneUtil.normalize(phoneCode);
            if (!PhoneUtil.isValid(phone)) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "开发期 mock 模式下 phoneCode 需直接传 11 位手机号");
            }
            log.info("[wechat] 开发期 mock 手机号解析 phone={}", phone);
            return phone;
        }
        // medi.wechat.mock-phone=false 但仍在 mock 模式：说明使用者想测真实解析却配了 mock。
        // 明确抛 9002 而不是返回假号码——返回假号码会让用户以为绑定成功，是最坏的结果。
        throw new BizException(ErrorCode.EXTERNAL_TIMEOUT,
                "当前为 mock 模式且已关闭 mock-phone，无法解析真实手机号；"
                        + "请配置微信凭证或开启 medi.wechat.mock-phone");
    }
}
