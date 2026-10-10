package com.dsc.medipartner.module.order.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dsc.medipartner.module.order.domain.entity.OrderStatusLog;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderEvent;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.domain.vo.OrderTimelineNodeVO;
import com.dsc.medipartner.module.order.mapper.OrderStatusLogMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderStatusLogService unit tests (contract §5.5, §7.9; appendix A/B.10).
 *
 * <p>Two behaviours matter most:
 * <ul>
 *   <li>timeline must come back in <b>ascending</b> time order (§5.5: nodes 按时间升序返回)</li>
 *   <li>legacy orders with no log rows must be reconstructed from the order's timestamps,
 *       marked 「历史数据补全」, so the timeline is never blank (§5.5 备注 3)</li>
 * </ul>
 */
class OrderStatusLogServiceImplTest {

    private static final String ORDER_NO = "MP20260921000000123456";

    private OrderStatusLogMapper logMapper;
    private OrderStatusLogServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                OrderStatusLog.class);
    }

    @BeforeEach
    void setUp() {
        logMapper = mock(OrderStatusLogMapper.class);
        service = new OrderStatusLogServiceImpl(logMapper);
    }

    private OrderStatusLog log(int toState, String event, String operatorType,
                               LocalDateTime at, String remark) {
        OrderStatusLog entry = new OrderStatusLog();
        entry.setOrderNo(ORDER_NO);
        entry.setToState(toState);
        entry.setEvent(event);
        entry.setOperatorType(operatorType);
        entry.setOperatorName("测试");
        entry.setRemark(remark);
        entry.setCreatedAt(at);
        return entry;
    }

    private ServiceOrder order(int status) {
        ServiceOrder order = new ServiceOrder();
        order.setId(1L);
        order.setOrderNo(ORDER_NO);
        order.setUserId(9L);
        order.setWorkerId(7L);
        order.setOrderStatus(status);
        order.setCreatedAt(LocalDateTime.of(2026, 9, 21, 10, 0, 0));
        order.setUpdatedAt(LocalDateTime.of(2026, 9, 21, 12, 0, 0));
        return order;
    }

    // ---------------- record ----------------

    @Test
    void recordWritesStatusTransitionWithTitleAndOperator() {
        service.record(ORDER_NO, OrderState.ACCEPTED.getCode(), OrderState.IN_SERVICE.getCode(),
                OrderEvent.START.name(), OrderStatusLogServiceImpl.OPERATOR_WORKER, 7L, "已在门诊大厅");

        ArgumentCaptor<OrderStatusLog> captor = ArgumentCaptor.forClass(OrderStatusLog.class);
        verify(logMapper).insert(captor.capture());
        OrderStatusLog entry = captor.getValue();
        assertThat(entry.getOrderNo()).isEqualTo(ORDER_NO);
        assertThat(entry.getFromState()).isEqualTo(OrderState.ACCEPTED.getCode());
        assertThat(entry.getToState()).isEqualTo(OrderState.IN_SERVICE.getCode());
        assertThat(entry.getEvent()).isEqualTo("START");
        assertThat(entry.getOperatorType()).isEqualTo("WORKER");
        assertThat(entry.getOperatorId()).isEqualTo(7L);
        assertThat(entry.getTitle()).isEqualTo("陪诊师已开始服务");
        assertThat(entry.getRemark()).isEqualTo("已在门诊大厅");
    }

    /** Non-state actions record an event but must not pollute the state trajectory. */
    @Test
    void recordActionLeavesStateColumnsEmpty() {
        service.recordAction(ORDER_NO, "WORKER_REPLY", OrderStatusLogServiceImpl.OPERATOR_WORKER,
                7L, null, "回复评价");

        ArgumentCaptor<OrderStatusLog> captor = ArgumentCaptor.forClass(OrderStatusLog.class);
        verify(logMapper).insert(captor.capture());
        assertThat(captor.getValue().getFromState()).isNull();
        assertThat(captor.getValue().getToState()).isNull();
        assertThat(captor.getValue().getTitle()).isEqualTo("陪诊师已回复评价");
    }

    @Test
    void systemOperatorIsNamedSystem() {
        service.record(ORDER_NO, OrderState.WAITING_ACCEPT.getCode(), OrderState.CANCELLED.getCode(),
                OrderEvent.TIMEOUT_CANCEL.name(), OrderStatusLogServiceImpl.OPERATOR_SYSTEM, null, "24小时无人接单");

        ArgumentCaptor<OrderStatusLog> captor = ArgumentCaptor.forClass(OrderStatusLog.class);
        verify(logMapper).insert(captor.capture());
        assertThat(captor.getValue().getOperatorName()).isEqualTo("系统");
    }

    // ---------------- timeline ----------------

    /** §5.5: nodes come back sorted ascending even if the rows are unordered. */
    @Test
    void timelineIsSortedAscending() {
        when(logMapper.selectList(any())).thenReturn(List.of(
                log(OrderState.COMPLETED.getCode(), "USER_CONFIRM", "USER",
                        LocalDateTime.of(2026, 9, 21, 15, 0), null),
                log(OrderState.WAITING_ACCEPT.getCode(), "CREATE", "USER",
                        LocalDateTime.of(2026, 9, 21, 10, 0), null),
                log(OrderState.IN_SERVICE.getCode(), "START", "WORKER",
                        LocalDateTime.of(2026, 9, 21, 12, 0), null)));

        List<OrderTimelineNodeVO> nodes = service.timeline(order(OrderState.COMPLETED.getCode()));

        assertThat(nodes).extracting(OrderTimelineNodeVO::getStatus)
                .containsExactly(OrderState.WAITING_ACCEPT.getCode(),
                        OrderState.IN_SERVICE.getCode(),
                        OrderState.COMPLETED.getCode());
    }

    @Test
    void timelineExposesStatusNameAndTitle() {
        when(logMapper.selectList(any())).thenReturn(List.of(
                log(OrderState.ACCEPTED.getCode(), "ACCEPT", "WORKER",
                        LocalDateTime.of(2026, 9, 21, 11, 0), null)));

        List<OrderTimelineNodeVO> nodes = service.timeline(order(OrderState.ACCEPTED.getCode()));

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getStatusName()).isEqualTo("已接单");
        assertThat(nodes.get(0).getTitle()).isEqualTo("陪诊师已接单");
        assertThat(nodes.get(0).getEvent()).isEqualTo("ACCEPT");
    }

    /** Recent N keeps chronological order but only the newest entries (§5.4). */
    @Test
    void recentTimelineKeepsNewestButStaysAscending() {
        when(logMapper.selectList(any())).thenReturn(List.of(
                log(OrderState.WAITING_ACCEPT.getCode(), "CREATE", "USER",
                        LocalDateTime.of(2026, 9, 21, 10, 0), null),
                log(OrderState.ACCEPTED.getCode(), "ACCEPT", "WORKER",
                        LocalDateTime.of(2026, 9, 21, 11, 0), null),
                log(OrderState.IN_SERVICE.getCode(), "START", "WORKER",
                        LocalDateTime.of(2026, 9, 21, 12, 0), null)));

        List<OrderTimelineNodeVO> nodes = service.recentTimeline(order(OrderState.IN_SERVICE.getCode()), 2);

        assertThat(nodes).extracting(OrderTimelineNodeVO::getStatus)
                .containsExactly(OrderState.ACCEPTED.getCode(), OrderState.IN_SERVICE.getCode());
    }

    // ---------------- legacy backfill ----------------

    /** No log rows: reconstruct from the order's timestamps and flag them as backfilled. */
    @Test
    void legacyOrderIsBackfilledFromTimestamps() {
        when(logMapper.selectList(any())).thenReturn(List.of());
        ServiceOrder legacy = order(OrderState.COMPLETED.getCode());
        legacy.setAcceptedAt(LocalDateTime.of(2026, 9, 21, 10, 30, 0));
        legacy.setStartedAt(LocalDateTime.of(2026, 9, 21, 11, 0, 0));
        legacy.setFinishedAt(LocalDateTime.of(2026, 9, 21, 13, 0, 0));
        legacy.setConfirmedAt(LocalDateTime.of(2026, 9, 21, 14, 0, 0));

        List<OrderTimelineNodeVO> nodes = service.timeline(legacy);

        assertThat(nodes).extracting(OrderTimelineNodeVO::getStatus).containsExactly(
                OrderState.WAITING_ACCEPT.getCode(), OrderState.ACCEPTED.getCode(),
                OrderState.IN_SERVICE.getCode(), OrderState.PENDING_CONFIRM.getCode(),
                OrderState.COMPLETED.getCode());
        assertThat(nodes).allSatisfy(node ->
                assertThat(node.getRemark()).isEqualTo("历史数据补全"));
        assertThat(nodes).extracting(OrderTimelineNodeVO::getOccurredAt).isSorted();
    }

    /** A brand new order has only createdAt, so the timeline holds just the CREATE node. */
    @Test
    void legacyOrderWithOnlyCreatedAtYieldsSingleNode() {
        when(logMapper.selectList(any())).thenReturn(List.of());

        List<OrderTimelineNodeVO> nodes = service.timeline(order(OrderState.WAITING_ACCEPT.getCode()));

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getStatus()).isEqualTo(OrderState.WAITING_ACCEPT.getCode());
        assertThat(nodes.get(0).getEvent()).isEqualTo("CREATE");
    }

    /** A terminal order without node timestamps still gets one node for its current state. */
    @Test
    void legacyCancelledOrderStillGetsCurrentStateNode() {
        when(logMapper.selectList(any())).thenReturn(List.of());
        ServiceOrder legacy = order(OrderState.CANCELLED.getCode());

        List<OrderTimelineNodeVO> nodes = service.timeline(legacy);

        assertThat(nodes).extracting(OrderTimelineNodeVO::getStatus)
                .contains(OrderState.CANCELLED.getCode());
    }
}
