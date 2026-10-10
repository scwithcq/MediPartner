package com.dsc.medipartner.module.order.domain.enums;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import lombok.Getter;

@Getter
public enum OrderState {

    WAITING_ACCEPT(10, "待接单"),
    ACCEPTED(20, "已接单"),
    IN_SERVICE(30, "服务中"),
    PENDING_CONFIRM(40, "待确认"),
    COMPLETED(50, "已完成"),
    CANCELLED(60, "已取消"),
    ABNORMAL(70, "异常处理中");

    private final int code;
    /** 中文状态名，由服务端下发给前端（附录 B.1），前端不硬编码中文 */
    private final String stateName;

    OrderState(int code, String stateName) {
        this.code = code;
        this.stateName = stateName;
    }

    public static OrderState of(int code) {
        for (OrderState state : values()) {
            if (state.code == code) {
                return state;
            }
        }
        throw new BizException(ErrorCode.ORDER_STATE_ILLEGAL);
    }

    /** 容错版查找：找不到返回 null（供只读展示场景使用，不抛异常） */
    public static OrderState ofNullable(Integer code) {
        if (code == null) {
            return null;
        }
        for (OrderState state : values()) {
            if (state.code == code) {
                return state;
            }
        }
        return null;
    }

    /** 状态中文名；非法状态码回落为空串而不是抛异常（读路径不应打断展示） */
    public static String nameOf(Integer code) {
        OrderState state = ofNullable(code);
        return state == null ? "" : state.stateName;
    }
}
