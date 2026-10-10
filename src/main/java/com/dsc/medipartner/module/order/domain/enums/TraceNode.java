package com.dsc.medipartner.module.order.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 服务节点标签（契约 §7.12 node）：陪诊师上传过程留痕时标注当前所处环节，
 * 用户侧时间轴与 AI 报告据此还原就诊过程。入参可空，空值不落 node 列。
 */
@Getter
@AllArgsConstructor
public enum TraceNode {

    ARRIVED("到场"),
    REGISTER("挂号"),
    WAITING("候诊"),
    VISIT("就诊"),
    PHARMACY("取药"),
    OTHER("其它");

    private final String label;

    public static boolean isValid(String name) {
        if (name == null) {
            return false;
        }
        for (TraceNode node : values()) {
            if (node.name().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
