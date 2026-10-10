package com.dsc.medipartner.module.user.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 信用中心（契约 §7.16 CreditVO）。
 */
@Data
public class CreditVO {

    private Integer creditScore;
    private String creditLevel;
    private String levelDesc;
    private Integer activeLimit;
    /** 距下一等级还差多少分；已满级时键缺失 */
    private Integer nextLevelScore;
    /** 近 30 天信用分曲线，最多 30 点 */
    private List<TrendPoint> trend;
    /** 最近 20 条变动 */
    private List<RecordItem> records;
    /** 信用规则说明（本地静态下发，阶段 1.5 起改 sys_dict CREDIT_RULE） */
    private List<RuleItem> rules;
    private Boolean canAccept;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrendPoint {
        private String date;
        private Integer score;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecordItem {
        /** 带正负号，如 "+2"、"-5" */
        private String changeScore;
        private String reason;
        private String relatedOrderNo;
        private LocalDateTime occurredAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuleItem {
        private String title;
        private String desc;
    }
}
