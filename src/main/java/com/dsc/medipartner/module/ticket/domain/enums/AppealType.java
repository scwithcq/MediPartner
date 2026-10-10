package com.dsc.medipartner.module.ticket.domain.enums;

import lombok.Getter;

import java.util.Locale;

/**
 * 申诉类型（契约附录 B.7）。
 * 区分发起方是为了在 §5.14 校验「用户只能用用户侧类型」——陪诊师侧类型（爽约/恶意差评）
 * 属阶段 4 后续的管理端链路，本轮 C 端接口只接受用户侧类型与 OTHER。
 */
@Getter
public enum AppealType {

    SERVICE_NOT_PROVIDED("陪诊师未按约提供服务", "USER"),
    ATTITUDE_ISSUE("服务态度问题", "USER"),
    EXTRA_CHARGE("线下额外索费", "USER"),
    NO_SHOW("陪诊师爽约", "USER"),
    USER_NO_SHOW("求助者爽约", "WORKER"),
    MALICIOUS_REVIEW("恶意差评", "WORKER"),
    WORKER_MARK("系统标记异常", "SYSTEM"),
    OTHER("其他", "BOTH");

    private final String label;
    /** 发起方：USER / WORKER / SYSTEM / BOTH */
    private final String applicantRole;

    AppealType(String label, String applicantRole) {
        this.label = label;
        this.applicantRole = applicantRole;
    }

    /** 大小写不敏感查找；未命中返回 null 由调用方抛 1001 */
    public static AppealType of(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 用户是否可发起该类型（USER 或 BOTH） */
    public boolean userInitiable() {
        return "USER".equals(applicantRole) || "BOTH".equals(applicantRole);
    }
}
