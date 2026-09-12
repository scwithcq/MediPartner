package com.dsc.medipartner.module.order;

import com.dsc.medipartner.module.order.domain.enums.OrderEvent;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineBuilder;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStateMachineRulesTest {

    private StateMachine<OrderState, OrderEvent> machine;

    @BeforeEach
    void setUp() throws Exception {
        StateMachineBuilder.Builder<OrderState, OrderEvent> builder = StateMachineBuilder.builder();
        builder.configureStates()
                .withStates()
                .initial(OrderState.WAITING_ACCEPT)
                .states(EnumSet.allOf(OrderState.class));
        builder.configureTransitions()
                .withExternal().source(OrderState.WAITING_ACCEPT).target(OrderState.ACCEPTED).event(OrderEvent.ACCEPT)
                .and().withExternal().source(OrderState.WAITING_ACCEPT).target(OrderState.CANCELLED).event(OrderEvent.USER_CANCEL)
                .and().withExternal().source(OrderState.ACCEPTED).target(OrderState.IN_SERVICE).event(OrderEvent.START)
                .and().withExternal().source(OrderState.IN_SERVICE).target(OrderState.PENDING_CONFIRM).event(OrderEvent.FINISH)
                .and().withExternal().source(OrderState.PENDING_CONFIRM).target(OrderState.COMPLETED).event(OrderEvent.USER_CONFIRM);
        machine = builder.build();
        machine.start();
    }

    @Test
    void happyPath() {
        assertThat(machine.getState().getId()).isEqualTo(OrderState.WAITING_ACCEPT);
        assertThat(machine.sendEvent(OrderEvent.ACCEPT)).isTrue();
        assertThat(machine.getState().getId()).isEqualTo(OrderState.ACCEPTED);
        assertThat(machine.sendEvent(OrderEvent.START)).isTrue();
        assertThat(machine.getState().getId()).isEqualTo(OrderState.IN_SERVICE);
        assertThat(machine.sendEvent(OrderEvent.FINISH)).isTrue();
        assertThat(machine.getState().getId()).isEqualTo(OrderState.PENDING_CONFIRM);
        assertThat(machine.sendEvent(OrderEvent.USER_CONFIRM)).isTrue();
        assertThat(machine.getState().getId()).isEqualTo(OrderState.COMPLETED);
    }

    @Test
    void cancelFromWaiting() {
        assertThat(machine.sendEvent(OrderEvent.USER_CANCEL)).isTrue();
        assertThat(machine.getState().getId()).isEqualTo(OrderState.CANCELLED);
    }

    @Test
    void illegalTransitionRejected() {
        assertThat(machine.sendEvent(OrderEvent.START)).isFalse();
        assertThat(machine.getState().getId()).isEqualTo(OrderState.WAITING_ACCEPT);
    }

    @Test
    void terminalStateHasNoOutgoing() {
        machine.sendEvent(OrderEvent.USER_CANCEL);
        assertThat(machine.getState().getId()).isEqualTo(OrderState.CANCELLED);
        assertThat(machine.sendEvent(OrderEvent.ACCEPT)).isFalse();
    }
}
