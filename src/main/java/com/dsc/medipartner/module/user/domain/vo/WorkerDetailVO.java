package com.dsc.medipartner.module.user.domain.vo;

import com.dsc.medipartner.module.review.domain.vo.PublicReviewVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 陪诊师详情（契约 §6.2）= WorkerCardVO 全部字段 + 扩展；intro 不截断。
 * starDistribution / avgStar 由 review 表聚合（V10 未加 star_distribution 冗余列，见进度文档偏差说明）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WorkerDetailVO extends WorkerCardVO {

    /** 此接口恒为 PASS（非 PASS 直接 2003） */
    private String auditStatus;
    /** 健康证/承诺书是否已验（health_cert_url 非空） */
    private Boolean certVerified;
    /** 身份证是否已认证（由 id_card_no 非空推导，不返回证件号本身） */
    private Boolean idCardVerified;
    /** 星级分布 {"5":120,"4":18,"3":2,"2":0,"1":1} */
    private Map<String, Integer> starDistribution;
    /** 平均星级一位小数字符串，无评价为 "5.0" */
    private String avgStar;
    /** 最近 3 条评价，元素结构同 §6.3 */
    private List<PublicReviewVO> recentReviews;
    /** 接单响应率；阶段 5 起真实统计，此前恒为 100 */
    private Integer responseRate;
    /** 入驻通过时间（audit_time） */
    private LocalDateTime joinedAt;
    /** 可服务城市，阶段 3 只有 city 单值 */
    private List<String> serviceCities;
    /** 平台徽章码值：REAL_NAME、HEALTH_CERT、TOP_RATED、ZERO_COMPLAINT */
    private List<String> badges;
    private LocalDateTime updatedAt;
}
