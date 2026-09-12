package com.dsc.medipartner.module.order.service;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.order.domain.enums.OrderEvent;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class OrderStateMachineService {

    private final StateMachineFactory<OrderState, OrderEvent> factory;

    public OrderStateMachineService(StateMachineFactory<OrderState, OrderEvent> factory) {
        this.factory = factory;
    }

    public OrderState transition(OrderState current, OrderEvent event) {
        StateMachine<OrderState, OrderEvent> machine = factory.getStateMachine(UUID.randomUUID().toString());
        machine.stop();
        machine.getStateMachineAccessor().doWithAllRegions(accessor ->
                accessor.resetStateMachine(new DefaultStateMachineContext<>(current, null, null, null)));
        machine.start();
        boolean accepted = machine.sendEvent(event);
        if (!accepted) {
            throw new BizException(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        return machine.getState().getId();
    }
}
