package com.dsc.medipartner.module.user.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * 陪诊师列表卡片（契约 §6.1）。distanceKm 属阶段 5（传坐标后按距离排序），当前不下发。
 */
@Data
public class WorkerCardVO {

    private String workerUserId;
    private String nickname;
    private String avatar;
    /** 脱敏真实姓名，形如 张某某，只显示姓 */
    private String realName;
    private String city;
    private Integer creditScore;
    private String creditLevel;
    /** 好评率百分比整数；无评价时为 100 且 reviewCount 为 0 */
    private Integer goodRate;
    private Integer reviewCount;
    private Integer serviceCount;
    private List<String> serviceTypes;
    private List<String> serviceTypeNames;
    /** 列表页截断至 60 字 */
    private String intro;
    /** 平台标签，如 实名认证、健康证已验 */
    private List<String> tags;
    /** 信用分不低于 60 且未达接单上限 */
    private Boolean accepting;
}
