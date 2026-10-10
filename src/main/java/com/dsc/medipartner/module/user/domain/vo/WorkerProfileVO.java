package com.dsc.medipartner.module.user.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 我的陪诊师资料（契约 §7.2）：对自己不脱敏 realName，身份证只回显掩码；
 * 汇总统计（avgStar / goodRate）统一由本 VO 提供，§7.20 不重复下发。
 */
@Data
public class WorkerProfileVO {

    private String workerUserId;
    private String realName;
    /** 身份证脱敏回显 3301**********1234，原始值永不返回 */
    private String idCardMasked;
    private Boolean idCardVerified;
    private String healthCertUrl;
    private String auditStatus;
    private String auditRemark;
    private LocalDateTime auditTime;
    private Integer creditScore;
    private String creditLevel;
    private String city;
    private List<String> serviceTypes;
    private List<String> serviceTypeNames;
    private String intro;
    /** PENDING 期间为 false，防止审核中改资料 */
    private Boolean canEdit;
    private LocalDateTime joinedAt;
    private LocalDateTime updatedAt;
    /** 好评率百分比整数 */
    private Integer goodRate;
    private Integer reviewCount;
    private Integer serviceCount;
    /** 平均星级一位小数字符串，如 "4.8" */
    private String avgStar;
}
