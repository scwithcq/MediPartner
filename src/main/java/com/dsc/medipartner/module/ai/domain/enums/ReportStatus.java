package com.dsc.medipartner.module.ai.domain.enums;

/**
 * AI 报告状态（契约 §5.10 status / ai_report.status）：
 * PROCESSING 生成中、DONE 已完成、FAILED 失败（重试耗尽后对用户暴露 failReason）。
 */
public enum ReportStatus {

    PROCESSING,
    DONE,
    FAILED;

    public static boolean isValid(String name) {
        if (name == null) {
            return false;
        }
        for (ReportStatus status : values()) {
            if (status.name().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
