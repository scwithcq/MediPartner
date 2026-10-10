package com.dsc.medipartner.module.dict.domain.enums;

import lombok.Getter;

import java.util.Locale;

/**
 * 字典类型白名单（契约 §8.2）。
 * 未知类型必须返回 1001 而不是空字典——否则前端下拉会静默变空，排查困难。
 */
@Getter
public enum DictType {

    CITY("已开通城市"),
    SERVICE_TYPE("服务类型"),
    CANCEL_REASON("用户取消原因"),
    ABNORMAL_TYPE("陪诊师标记异常原因"),
    REVIEW_TAG("评价印象标签"),
    CREDIT_RULE("信用分规则说明");

    private final String label;

    DictType(String label) {
        this.label = label;
    }

    /** 严格匹配（大小写不敏感 + 去空白），未命中返回 null 由调用方抛 1001 */
    public static DictType of(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (DictType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
