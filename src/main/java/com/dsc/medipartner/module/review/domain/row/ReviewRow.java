package com.dsc.medipartner.module.review.domain.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * review JOIN service_order 的查询投影（§6.3 / §7.20 / §6.2 最近评价共用），由 ReviewService 组装 VO。
 * impactScore 用相关子查询取最近一条信用流水，避免 N+1。
 */
@Data
public class ReviewRow {

    private Long reviewId;
    private String orderNo;
    private Long reviewerId;
    private String reviewerRole;
    private Integer star;
    private String content;
    private String tags;
    private String imageUrls;
    private Boolean anonymous;
    private String appendContent;
    private LocalDateTime appendAt;
    private String replyContent;
    private LocalDateTime replyAt;
    private LocalDateTime createdAt;
    /** service_order.service_type 码值 */
    private String orderServiceType;
    private LocalDateTime orderServiceTime;
    /** credit_change_log.change_score，可为 null */
    private Integer impactScore;
}
