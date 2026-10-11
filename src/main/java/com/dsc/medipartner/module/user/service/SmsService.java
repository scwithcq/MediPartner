package com.dsc.medipartner.module.user.service;

import com.dsc.medipartner.module.user.domain.dto.SmsCodeRequest;
import com.dsc.medipartner.module.user.domain.vo.SmsCodeVO;

/**
 * 短信验证码服务（契约 §2.5 / §2.6）。
 */
public interface SmsService {

    /**
     * 发送验证码。
     *
     * <p>限流：同手机号 60 秒一次（契约 §2.5），超限抛 {@code 1005}。
     * 开发期 {@code medi.sms.mock=true} 时不接真实网关，固定验证码并回显。
     */
    SmsCodeVO sendCode(SmsCodeRequest request);

    /**
     * 校验验证码；<b>校验成功即删除</b>，保证一个验证码只能用一次。
     *
     * @throws com.dsc.medipartner.common.exception.BizException
     *         验证码错误、已过期或已被使用（{@code 2004}）
     */
    void verifyCode(String scene, String phone, String code);
}
