package com.dsc.medipartner.module.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.module.review.domain.dto.ReviewQuery;
import com.dsc.medipartner.module.review.domain.dto.WorkerReviewQuery;
import com.dsc.medipartner.module.review.domain.entity.Review;
import com.dsc.medipartner.module.review.domain.row.ReviewRow;
import com.dsc.medipartner.module.review.domain.row.ReviewStatsRow;
import com.dsc.medipartner.module.review.domain.row.StarCountRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评价查询（契约 §6.3 / §7.20 / §6.2）。
 * 一律只出 review_status=1 的生效评价；被管理端仲裁屏蔽的评价对用户与陪诊师都不可见。
 */
@Mapper
public interface ReviewMapper extends BaseMapper<Review> {

    /** 公共投影列，各查询共用；impactScore 用相关子查询取最近一条信用流水，避免 N+1 */
    String COLUMNS = """
            SELECT r.id AS reviewId, r.order_no AS orderNo, r.reviewer_id AS reviewerId,
                   r.reviewer_role AS reviewerRole, r.star AS star, r.content AS content, r.tags AS tags,
                   r.image_urls AS imageUrls, r.anonymous AS anonymous,
                   r.append_content AS appendContent, r.append_at AS appendAt,
                   r.reply_content AS replyContent, r.reply_at AS replyAt, r.created_at AS createdAt,
                   o.service_type AS orderServiceType, o.service_time AS orderServiceTime,
                   (SELECT c.change_score FROM credit_change_log c
                     WHERE c.review_id = r.id ORDER BY c.id DESC LIMIT 1) AS impactScore
            FROM review r
            LEFT JOIN service_order o ON o.order_no = r.order_no
            """;

    /** §6.3 陪诊师评价列表（公开视角，匿名脱敏在服务层做） */
    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE r.worker_id = #{workerId} AND r.reviewer_role = 'USER' AND r.review_status = 1
            <if test="q.star != null"> AND r.star = #{q.star}</if>
            <if test="q.hasContent != null and q.hasContent"> AND r.content IS NOT NULL AND r.content != ''</if>
            <if test="q.hasReply != null and q.hasReply"> AND r.reply_content IS NOT NULL AND r.reply_content != ''</if>
            ORDER BY r.created_at DESC, r.id DESC
            </script>
            """)
    IPage<ReviewRow> pagePublicReviews(IPage<ReviewRow> page,
                                      @Param("workerId") Long workerId,
                                      @Param("q") ReviewQuery query);

    /** §7.20 陪诊师「收到的评价」：unreplied 需同时满足未回复且仍在 7 天窗口内 */
    @Select("""
            <script>
            """ + COLUMNS + """
            WHERE r.worker_id = #{workerId} AND r.reviewer_role = 'USER' AND r.review_status = 1
            <if test="q.star != null"> AND r.star = #{q.star}</if>
            <if test="q.unreplied != null and q.unreplied">
                AND (r.reply_content IS NULL OR r.reply_content = '') AND r.created_at &gt;= #{replyDeadline}
            </if>
            ORDER BY r.created_at DESC, r.id DESC
            </script>
            """)
    IPage<ReviewRow> pageWorkerReviews(IPage<ReviewRow> page,
                                      @Param("workerId") Long workerId,
                                      @Param("q") WorkerReviewQuery query,
                                      @Param("replyDeadline") LocalDateTime replyDeadline);

    /** §6.2 详情页最近 N 条评价 */
    @Select(COLUMNS + """
            WHERE r.worker_id = #{workerId} AND r.reviewer_role = 'USER' AND r.review_status = 1
            ORDER BY r.created_at DESC, r.id DESC
            LIMIT #{limit}
            """)
    List<ReviewRow> recentPublicReviews(@Param("workerId") Long workerId, @Param("limit") int limit);

    /** §7.21 回复后回读完整投影（含订单信息与 impactScore） */
    @Select(COLUMNS + """
            WHERE r.id = #{reviewId}
            """)
    ReviewRow rowById(@Param("reviewId") Long reviewId);

    /** §6.2 星级分布 */
    @Select("""
            SELECT r.star AS star, COUNT(*) AS cnt
            FROM review r
            WHERE r.worker_id = #{workerId} AND r.reviewer_role = 'USER' AND r.review_status = 1
            GROUP BY r.star
            """)
    List<StarCountRow> countByStar(@Param("workerId") Long workerId);

    /** §5.11 联动：重算好评率与平均星级（只统计生效评价） */
    @Select("""
            SELECT COUNT(*) AS reviewCount,
                   COALESCE(SUM(CASE WHEN r.star >= 4 THEN 1 ELSE 0 END), 0) AS goodCount,
                   AVG(r.star) AS avgStar
            FROM review r
            WHERE r.worker_id = #{workerId} AND r.reviewer_role = 'USER' AND r.review_status = 1
            """)
    ReviewStatsRow stats(@Param("workerId") Long workerId);
}
