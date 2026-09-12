package com.dsc.medipartner.module.order.domain.enums;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import lombok.Getter;

@Getter
public enum OrderState {

    WAITING_ACCEPT(10),
    ACCEPTED(20),
    IN_SERVICE(30),
    PENDING_CONFIRM(40),
    COMPLETED(50),
    CANCELLED(60),
    ABNORMAL(70);

    private final int code;

    OrderState(int code) {
        this.code = code;
    }

    public static OrderState of(int code) {
        for (OrderState state : values()) {
            if (state.code == code) {
                return state;
            }
        }
        throw new BizException(ErrorCode.ORDER_STATE_ILLEGAL);
    }
}
