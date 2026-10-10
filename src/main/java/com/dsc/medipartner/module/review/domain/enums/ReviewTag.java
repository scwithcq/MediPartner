package com.dsc.medipartner.module.review.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 评价印象标签（契约 §5.11 tags / §8.3 字典 REVIEW_TAG）。
 * 入参接受码值，出参按契约给中文名（§6.3 直接展示「准时到达」这类文案），前端不做映射。
 */
@Getter
@AllArgsConstructor
public enum ReviewTag {

    PUNCTUAL("准时到达", 1),
    PATIENT("耐心细致", 1),
    PROFESSIONAL("专业可靠", 1),
    WARM("热心周到", 1),
    EFFICIENT("高效便捷", 1);

    private final String label;
    /** 该标签适用的最低星级 */
    private final int starMin;

    /** 码值或中文名都能识别，识别不了返回 null（调用方决定 1001） */
    public static ReviewTag of(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        for (ReviewTag tag : values()) {
            if (tag.name().equalsIgnoreCase(trimmed) || tag.label.equals(trimmed)) {
                return tag;
            }
        }
        return null;
    }
}
