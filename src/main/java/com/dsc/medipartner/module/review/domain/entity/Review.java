package com.dsc.medipartner.module.review.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsc.medipartner.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 评价（契约 V10 review 表）。一单一评按角色维度：USER 评陪诊师、WORKER 评用户各一条，
 * 唯一键 uk_order_role(order_no, reviewer_role) 做数据库级兜底。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("review")
public class Review extends BaseEntity {

    private String orderNo;
    /** 陪诊师用户 ID（冗余列，便于陪诊师侧查询） */
    private Long workerId;
    private Long reviewerId;
    /** USER / WORKER */
    private String reviewerRole;
    private Integer star;
    private String content;
    /** 印象标签码值，逗号分隔 */
    private String tags;
    /** 配图相对路径，逗号分隔，最多 3 张 */
    private String imageUrls;
    /** 1 匿名 0 公开 */
    private Boolean anonymous;
    private String appendContent;
    private LocalDateTime appendAt;
    private String replyContent;
    private LocalDateTime replyAt;
    /** 1 生效 0 被仲裁屏蔽 */
    private Integer reviewStatus;
}
