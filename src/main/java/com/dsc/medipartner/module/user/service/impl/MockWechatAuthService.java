package com.dsc.medipartner.module.user.service.impl;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.util.PhoneUtil;
import com.dsc.medipartner.module.user.service.WechatAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 开发阶段的模拟微信登录实现，直接把 code 映射为 openid。
 * 接入真实微信后替换为 code2session 实现即可。
 */
@Slf4j
@Service
public class MockWechatAuthService implements WechatAuthService {

    /**
     * 开发期开关（契约 §2.4）：true 时把 phoneCode 直接当手机号，
     * 便于没有已认证小程序也能本地联调绑定流程。
     */
    @Value("${medi.wechat.mock-phone:true}")
    private boolean mockPhone;

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
        if (mockPhone) {
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
        // 阶段 6：调用微信 phonenumber.getPhoneNumber 换取真实号码。
        // 未接入时明确抛 9002（外部服务超时/不可用），而不是返回假号码——
        // 返回假号码会让用户以为自己绑定成功，是最坏的结果。
        throw new BizException(ErrorCode.EXTERNAL_TIMEOUT, "微信手机号接口尚未接入（阶段 6）");
    }
}
