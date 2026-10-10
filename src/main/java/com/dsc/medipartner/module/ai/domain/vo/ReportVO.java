package com.dsc.medipartner.module.ai.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 陪诊报告（契约 §5.10）。status=PROCESSING 时仅 orderNo/status 有值，前端轮询；
 * status=DONE 时下发完整正文；status=FAILED 且重试耗尽时下发 failReason。
 * visitInfo 在读取时由订单实时派生（hospital 取 order.hospitalPref），不落 ai_report 表，
 * 故 V10 无需为就诊信息加列；department/doctor/diagnosis 阶段 3 无数据源，保持 null（JSON 键缺失）。
 */
@Data
public class ReportVO {

    private String orderNo;
    /** PROCESSING / DONE / FAILED */
    private String status;
    private String title;
    /** 摘要，≤300 字 */
    private String summary;
    private List<ReportSection> sections;
    /** 后续建议，≤5 条 */
    private List<String> advice;
    private VisitInfo visitInfo;
    /** 免责声明，前端必须展示 */
    private String disclaimer;
    /** 生成厂商：deepseek / qwen / zhipu */
    private String provider;
    /** 是否降级模板生成 */
    private Boolean degraded;
    private LocalDateTime generatedAt;
    /** 失败原因（用户可见文案），仅 FAILED 且重试耗尽时下发 */
    private String failReason;

    /** 报告正文分节 */
    @Data
    public static class ReportSection {
        private String heading;
        private String content;

        public ReportSection() {
        }

        public ReportSection(String heading, String content) {
            this.heading = heading;
            this.content = content;
        }
    }

    /** 就诊信息（读取时由订单派生） */
    @Data
    public static class VisitInfo {
        /** 意向医院，取 order.hospitalPref */
        private String hospital;
        /** 阶段 3 无数据源，预留 */
        private String department;
        /** 阶段 3 无数据源，预留 */
        private String doctor;
        /** 阶段 3 无数据源，预留 */
        private String diagnosis;

        public VisitInfo() {
        }

        public VisitInfo(String hospital) {
            this.hospital = hospital;
        }
    }
}
