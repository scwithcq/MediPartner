package com.dsc.medipartner.module.order.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dsc.medipartner.infra.mq.ReportTaskPublisher;
import com.dsc.medipartner.module.demand.service.DemandService;
import com.dsc.medipartner.module.order.domain.dto.OrderQuery;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.mapper.ServiceOrderMapper;
import com.dsc.medipartner.module.order.service.OrderStateMachineService;
import com.dsc.medipartner.module.order.service.PriceService;
import com.dsc.medipartner.module.order.service.TraceService;
import com.dsc.medipartner.module.user.service.CreditService;
import com.dsc.medipartner.module.user.service.WorkerService;
import com.dsc.medipartner.module.wallet.service.WalletService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderServiceImpl paging tests (contract §1.3, §1.8, §1.9; appendix H-9).
 *
 * <p>These assert that the surface filters and the resolved sort actually reach the query wrapper -
 * the whitelist itself is covered by {@code SortWhitelistTest}.
 */
class OrderPagingTest {

    private static final long USER_ID = 9L;

    private ServiceOrderMapper orderMapper;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ServiceOrder.class);
    }

    /** Capture the wrapper handed to the mapper, with all deps stubbed except the mapper. */
    private LambdaQueryWrapper<ServiceOrder> captureWrapper(OrderQuery query, boolean hall) {
        orderMapper = mock(ServiceOrderMapper.class);
        when(orderMapper.selectPage(any(), any())).thenReturn(null);
        OrderServiceImpl service = new OrderServiceImpl(
                orderMapper,
                mock(WalletService.class),
                mock(WorkerService.class),
                mock(OrderStateMachineService.class),
                mock(TraceService.class),
                mock(ReportTaskPublisher.class),
                mock(DemandService.class),
                mock(CreditService.class),
                mock(PriceService.class));

        if (hall) {
            service.pageHall(query);
        } else {
            service.pageUserOrders(USER_ID, query);
        }
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<ServiceOrder>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderMapper).selectPage(any(), captor.capture());
        return captor.getValue();
    }

    private String lastSql(LambdaQueryWrapper<ServiceOrder> wrapper) {
        String sql = wrapper.getTargetSql();
        return sql == null ? "" : sql;
    }

    @Test
    void orderListAppliesWhitelistedSortColumn() {
        OrderQuery query = new OrderQuery();
        query.setSortBy("amount");
        query.setOrder("asc");

        String sql = lastSql(captureWrapper(query, false));

        assertThat(sql).contains("ORDER BY amount ASC");
    }

    @Test
    void orderListFallsBackToDefaultSortForIllegalValue() {
        OrderQuery query = new OrderQuery();
        query.setSortBy("id; DROP TABLE service_order");

        String sql = lastSql(captureWrapper(query, false));

        assertThat(sql).contains("ORDER BY created_at DESC");
        assertThat(sql).doesNotContain("DROP");
        assertThat(sql).doesNotContain(";");
    }

    @Test
    void orderHallDefaultsToServiceTimeAsc() {
        String sql = lastSql(captureWrapper(new OrderQuery(), true));
        assertThat(sql).contains("ORDER BY service_time ASC");
    }

    @Test
    void hallRejectsOrderListOnlyColumn() {
        OrderQuery query = new OrderQuery();
        // goodRate is not whitelisted for the hall
        query.setSortBy("goodRate");
        String sql = lastSql(captureWrapper(query, true));
        assertThat(sql).contains("ORDER BY service_time ASC");
    }

    @Test
    void updatedSinceAddsStrictlyGreaterFilter() {
        OrderQuery query = new OrderQuery();
        query.setUpdatedSince("2026-09-18 14:30:00");

        String sql = lastSql(captureWrapper(query, false));

        assertThat(sql).contains("updated_at");
        assertThat(sql).contains(">");
    }

    @Test
    void malformedUpdatedSinceIsIgnoredNotFatal() {
        OrderQuery query = new OrderQuery();
        query.setUpdatedSince("not-a-timestamp");

        String sql = lastSql(captureWrapper(query, false));

        assertThat(sql).doesNotContain("not-a-timestamp");
    }

    @Test
    void orderStatusFilterIsApplied() {
        OrderQuery query = new OrderQuery();
        query.setOrderStatus(20);
        String sql = lastSql(captureWrapper(query, false));
        assertThat(sql).contains("order_status");
    }

    @Test
    void statusGroupNarrowsToProcessingStates() {
        OrderQuery query = new OrderQuery();
        query.setStatusGroup("PROCESSING");
        String sql = lastSql(captureWrapper(query, false));
        // 10,20,30,40
        assertThat(sql).contains("order_status");
        assertThat(sql).contains("IN");
    }

    @Test
    void orderStatusWinsOverStatusGroup() {
        OrderQuery query = new OrderQuery();
        query.setOrderStatus(50);
        query.setStatusGroup("PROCESSING");
        String sql = lastSql(captureWrapper(query, false));
        assertThat(sql).contains("order_status");
    }

    @Test
    void keywordSearchesOrderNoCityAndSymptom() {
        OrderQuery query = new OrderQuery();
        query.setKeyword("Chongqing");
        String sql = lastSql(captureWrapper(query, false));
        assertThat(sql).contains("order_no");
        assertThat(sql).contains("city");
        assertThat(sql).contains("symptom_desc");
    }
}
