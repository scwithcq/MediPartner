package com.dsc.medipartner.module.review.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对外评价（契约 §6.3）：陪诊师详情页公开展示，刻意不含 orderNo，防止被用来反查他人订单；
 * 匿名脱敏在服务端完成。tags 直接给中文名（「准时到达」这类文案）。
 */
@Data
public class PublicReviewVO {

    private String reviewId;
    private Integer star;
    private String content;
    private List<String> imageUrls;
    private Boolean anonymous;
    /** 匿名时后端直接返回「匿名用户」 */
    private String userNickname;
    /** 匿名时返回默认头像路径 */
    private String userAvatar;
    /** 印象标签中文名 */
    private List<String> tags;
    private String replyContent;
    private LocalDateTime replyAt;
    private String appendContent;
    private LocalDateTime appendAt;
    /** 该单服务类型中文名 */
    private String orderServiceType;
    private LocalDateTime createdAt;
}
