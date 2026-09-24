package com.dsc.medipartner.module.order.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 服务类型（接口文档 附录 B.6），与 service_price_rule 一一对应（阶段 1.5 建表）。
 * 阶段 2 先落枚举：AI 推荐 suggestServiceType 输出这里的枚举名。
 */
@Getter
@AllArgsConstructor
public enum ServiceType {

    ACCOMPANY("全程陪诊"),
    HALF_DAY("半日陪护"),
    FULL_DAY("全日陪护"),
    PICKUP_REPORT("代取报告"),
    CONSULT_AGENT("代问诊");

    private final String label;

    public static boolean isValid(String name) {
        if (name == null) {
            return false;
        }
        for (ServiceType type : values()) {
            if (type.name().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
