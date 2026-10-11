package com.dsc.medipartner.module.user.service;

public interface WechatAuthService {

    String resolveOpenid(String code);

    /**
     * 用 {@code <button open-type="getPhoneNumber">} 回调的 code 换取真实手机号
     * （契约 §2.4）。
     *
     * <p>开发期 {@code medi.wechat.mock-phone=true} 时直接把 phoneCode 当手机号返回，
     * 便于本地联调；接真实微信后替换为本方法的实现（调用
     * {@code phonenumber.getPhoneNumber}），<b>接口签名不变</b>。
     *
     * @return 真实手机号（11 位）
     * @throws com.dsc.medipartner.common.exception.BizException
     *         phoneCode 为空（{@code 1001}）或微信接口失败/超时（{@code 9002}）
     */
    String resolvePhone(String phoneCode);
}
