package com.dsc.medipartner.module.user.service.impl;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.user.service.WechatAuthService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 开发阶段的模拟微信登录实现，直接把 code 映射为 openid。
 * 接入真实微信后替换为 code2session 实现即可。
 */
@Service
public class MockWechatAuthService implements WechatAuthService {

    @Override
    public String resolveOpenid(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "code不能为空");
        }
        return "mock_openid_" + code;
    }
}
