package com.dsc.medipartner.module.user.domain.dto;

import lombok.Data;

/**
 * §2.4 微信手机号快捷绑定请求。
 */
@Data
public class WechatPhoneRequest {

    /**
     * {@code <button open-type="getPhoneNumber">} 回调里的 code
     * （新版接口，后端用 {@code phonenumber.getPhoneNumber} 换真实号码）。
     *
     * <p>开发期 {@code medi.wechat.mock-phone=true} 时直接把本值当手机号入库。
     */
    private String phoneCode;
}
