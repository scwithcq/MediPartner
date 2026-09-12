package com.dsc.medipartner.module.order.config;

import com.dsc.medipartner.module.order.domain.enums.OrderEvent;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.EnumStateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

import java.util.EnumSet;

@Configuration
@EnableStateMachineFactory
public class OrderStateMachineConfig extends EnumStateMachineConfigurerAdapter<OrderState, OrderEvent> {

    @Override
    public void configure(StateMachineStateConfigurer<OrderState, OrderEvent> states) throws Exception {
        states.withStates()
                .initial(OrderState.WAITING_ACCEPT)
                .states(EnumSet.allOf(OrderState.class));
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<OrderState, OrderEvent> transitions) throws Exception {
        transitions
                .withExternal().source(OrderState.WAITING_ACCEPT).target(OrderState.ACCEPTED).event(OrderEvent.ACCEPT).and()
                .withExternal().source(OrderState.WAITING_ACCEPT).target(OrderState.CANCELLED).event(OrderEvent.USER_CANCEL).and()
                .withExternal().source(OrderState.WAITING_ACCEPT).target(OrderState.CANCELLED).event(OrderEvent.TIMEOUT_CANCEL).and()
                .withExternal().source(OrderState.ACCEPTED).target(OrderState.IN_SERVICE).event(OrderEvent.START).and()
                .withExternal().source(OrderState.IN_SERVICE).target(OrderState.PENDING_CONFIRM).event(OrderEvent.FINISH).and()
                .withExternal().source(OrderState.PENDING_CONFIRM).target(OrderState.COMPLETED).event(OrderEvent.USER_CONFIRM).and()
                .withExternal().source(OrderState.PENDING_CONFIRM).target(OrderState.COMPLETED).event(OrderEvent.AUTO_CONFIRM).and()
                .withExternal().source(OrderState.IN_SERVICE).target(OrderState.ABNORMAL).event(OrderEvent.MARK_ABNORMAL).and()
                .withExternal().source(OrderState.ABNORMAL).target(OrderState.CANCELLED).event(OrderEvent.ADMIN_CANCEL).and()
                .withExternal().source(OrderState.ABNORMAL).target(OrderState.CANCELLED).event(OrderEvent.ADMIN_REFUND);
    }
}
