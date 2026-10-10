package com.dsc.medipartner.module.user.domain.vo;

import com.dsc.medipartner.module.order.domain.vo.OrderCardVO;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 陪诊师工作台首页聚合（契约 §7.1）。
 * 一次请求返回首页全部数据，弱网下的首屏时间是答辩观感重点。
 *
 * <p>{@code auditStatus != PASS} 时统计字段仍返回默认值（0 / "0.00"），前端只按
 * {@code auditStatus} 决定渲染哪套 UI，不必判空。
 */
@Data
public class WorkerOverviewVO {

    private Long workerUserId;
    private String nickname;
    private String avatar;
    /** PENDING / REJECT / PASS */
    private String auditStatus;
    /** 驳回原因，仅 REJECT 时有值 */
    private String auditRemark;
    private Integer creditScore;
    /** 信用等级，同 §6.1 */
    private String creditLevel;
    /** 当前是否可接单（信用分 ≥ 门槛且进行中订单数未达上限） */
    private Boolean accepting;
    /** 进行中订单数（状态 20/30/40） */
    private Long activeCount;
    /** 接单上限（信用分低于阈值时降为低信用上限） */
    private Integer activeLimit;
    /** 接单大厅当前可抢单数，首页红点 */
    private Long hallCount;
    /** 今日待服务订单数 */
    private Long todayCount;
    /** 最需要处理的一单摘要；无则键缺失 */
    private OrderCardVO processingOrder;
    /** 本月已结算收益 */
    private BigDecimal monthEarning;
    /** 本月完成单数 */
    private Long monthOrderCount;
    /** 提现处理中金额（阶段 4） */
    private BigDecimal pendingWithdraw;
    /** 未读新评价数（阶段 3） */
    private Long newReviewCount;
    /** 平台公告（阶段 5），最多 3 条 */
    private List<Notice> notices;
    /** 服务器当前时间，前端校准倒计时 */
    private LocalDateTime serverTime;

    @Data
    public static class Notice {
        private String title;
        private String url;
        private LocalDateTime publishedAt;
    }
}
