package com.dsc.medipartner.module.review.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单评价（契约 §5.12）：用户查自己订单上的双向评价，含 orderNo；
 * tags 是码值、tagNames 是后端下发的中文名，前端免映射。
 */
@Data
public class ReviewVO {

    private String reviewId;
    private String orderNo;
    /** USER / WORKER */
    private String reviewerRole;
    /** 匿名时为「匿名用户」 */
    private String reviewerName;
    /** 匿名时为默认头像路径 */
    private String reviewerAvatar;
    private Integer star;
    private String content;
    private List<String> tags;
    private List<String> tagNames;
    private List<String> imageUrls;
    private Boolean anonymous;
    private String appendContent;
    private LocalDateTime appendAt;
    private String replyContent;
    private LocalDateTime replyAt;
    private LocalDateTime createdAt;
}
