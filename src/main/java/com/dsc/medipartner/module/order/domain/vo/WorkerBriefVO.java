package com.dsc.medipartner.module.order.domain.vo;

import lombok.Data;

/**
 * 订单中的「对方」摘要（契约 §5.3 的 {@code worker} 字段）。
 * 求助者视角看到的是陪诊师；陪诊师视角看到的是求助者（字段各自按需赋值）。
 */
@Data
public class WorkerBriefVO {

    /** 陪诊师/求助者的用户 ID（跳详情用） */
    private Long workerUserId;
    private String nickname;
    private String avatar;
    /** 脱敏真实姓名（§1.11），仅详情页下发 */
    private String realName;
    /** 脱敏手机号 138****1234 */
    private String maskedPhone;
    private Integer creditScore;
    private Integer goodRate;
    private Integer serviceCount;
    private String intro;
    /** 陪诊师信用等级 */
    private String creditLevel;
}
