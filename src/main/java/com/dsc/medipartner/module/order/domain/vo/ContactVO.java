package com.dsc.medipartner.module.order.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 联系对方（契约 §5.6 求助者查陪诊师、§7.10 陪诊师查求助者，结构完全一致）。
 *
 * <p>隐私设计：真实手机号一律脱敏；可拨打的号码是 {@code orderNo} 哈希生成的<b>稳定</b>
 * 虚拟号，双方查到的是同一个号。
 */
@Data
public class ContactVO {

    /** 对方角色：用户查询时为 WORKER，陪诊师查询时为 USER */
    private String peerRole;
    /** 对方姓名，脱敏 张** */
    private String peerName;
    /** 脱敏手机号 138****1234 */
    private String maskedPhone;
    /** 虚拟号（可拨打），形如 170xxxxxxx */
    private String virtualPhone;
    /** 虚拟号失效时间（默认订单完成后 24 小时） */
    private LocalDateTime virtualExpireAt;
    /** 是否允许拨打（状态在 20~50 之间为 true） */
    private Boolean canCall;
    /** 提示文案，如「为保护隐私，请通过虚拟号联系」 */
    private String tip;
}
