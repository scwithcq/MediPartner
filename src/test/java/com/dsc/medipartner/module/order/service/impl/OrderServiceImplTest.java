package com.dsc.medipartner.module.order.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.security.LoginUser;
import com.dsc.medipartner.infra.mq.ReportTaskPublisher;
import com.dsc.medipartner.module.demand.service.DemandService;
import com.dsc.medipartner.module.order.domain.dto.CreateOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.FinishOrderRequest;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.domain.vo.FinishResultVO;
import com.dsc.medipartner.module.order.mapper.ServiceOrderMapper;
import com.dsc.medipartner.module.order.service.OrderStateMachineService;
import com.dsc.medipartner.module.order.service.TraceService;
import com.dsc.medipartner.module.user.domain.enums.CreditChangeType;
import com.dsc.medipartner.module.user.service.CreditService;
import com.dsc.medipartner.module.user.service.WorkerService;
import com.dsc.medipartner.module.wallet.service.WalletService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderServiceImpl 纯单测：下单回写工单、结束服务落凭证并投递报告、确认结算联动信用分与完成单数。
 */
class OrderServiceImplTest {

    private static final long USER_ID = 9L;
    private static final long WORKER_ID = 7L;
    private static final String ORDER_NO = "MP20260921000000123456";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ServiceOrderMapper orderMapper;
    private WalletService walletService;
    private WorkerService workerService;
    private OrderStateMachineService stateMachineService;
    private TraceService traceService;
    private ReportTaskPublisher reportTaskPublisher;
    private DemandService demandService;
    private CreditService creditService;
    private OrderServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ServiceOrder.class);
    }

    @BeforeEach
    void setUp() {
        orderMapper = mock(ServiceOrderMapper.class);
        walletService = mock(WalletService.class);
        workerService = mock(WorkerService.class);
        stateMachineService = mock(OrderStateMachineService.class);
        traceService = mock(TraceService.class);
        reportTaskPublisher = mock(ReportTaskPublisher.class);
        demandService = mock(DemandService.class);
        creditService = mock(CreditService.class);
        service = new OrderServiceImpl(orderMapper, walletService, workerService, stateMachineService,
                traceService, reportTaskPublisher, demandService, creditService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void login(long userId) {
        LoginUser loginUser = new LoginUser(userId, Set.of("ROLE_USER"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    private ServiceOrder order(int status, long ownerUserId, long workerId) {
        ServiceOrder order = new ServiceOrder();
        order.setId(1L);
        order.setOrderNo(ORDER_NO);
        order.setUserId(ownerUserId);
        order.setWorkerId(workerId);
        order.setOrderStatus(status);
        order.setAmount(new BigDecimal("200.00"));
        order.setVersion(0);
        return order;
    }

    private CreateOrderRequest createRequest(Long demandId) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setSymptomDesc("胸口疼");
        request.setCity("重庆");
        request.setHospitalPref("重医附一院");
        request.setServiceTime(LocalDateTime.now().plusDays(1));
        request.setAmount(new BigDecimal("200.00"));
        request.setDemandId(demandId);
        return request;
    }

    private FinishOrderRequest finishRequest() {
        FinishOrderRequest request = new FinishOrderRequest();
        request.setVoucherUrls(List.of("/files/ORDER_VOUCHER/1.jpg"));
        request.setGpsLat("29.56");
        request.setGpsLng("106.55");
        request.setWatermarkTime(LocalDateTime.now().format(TS));
        request.setServiceSummary("服务完成");
        request.setActualHours(new BigDecimal("2.0"));
        return request;
    }

    @Test
    void createSetsDemandIdAndMarksOrdered() {
        login(USER_ID);
        String orderNo = service.create(createRequest(100L));

        ArgumentCaptor<ServiceOrder> captor = ArgumentCaptor.forClass(ServiceOrder.class);
        verify(orderMapper).insert(captor.capture());
        ServiceOrder inserted = captor.getValue();
        assertThat(orderNo).startsWith("MP");
        assertThat(inserted.getOrderNo()).isEqualTo(orderNo);
        assertThat(inserted.getUserId()).isEqualTo(USER_ID);
        assertThat(inserted.getDemandId()).isEqualTo(100L);
        assertThat(inserted.getOrderStatus()).isEqualTo(OrderState.WAITING_ACCEPT.getCode());
        assertThat(inserted.getServiceType()).isEqualTo("ACCOMPANY");
        verify(walletService).freeze(eq(USER_ID), eq(orderNo), any());
        verify(demandService).markOrdered(100L, orderNo);
    }

    @Test
    void createWithoutDemandIdSkipsMarkOrdered() {
        login(USER_ID);
        service.create(createRequest(null));
        verify(demandService, never()).markOrdered(any(), any());
    }

    @Test
    void finishSavesVouchersAndPublishesReport() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.IN_SERVICE.getCode(), USER_ID, WORKER_ID));
        when(orderMapper.update(any(), any())).thenReturn(1);
        FinishOrderRequest request = finishRequest();

        FinishResultVO vo = service.finish(WORKER_ID, ORDER_NO, request);

        verify(traceService).saveVouchers(WORKER_ID, ORDER_NO, request);
        verify(reportTaskPublisher).publishReportTask(ORDER_NO);
        assertThat(vo.getOrderStatus()).isEqualTo(OrderState.PENDING_CONFIRM.getCode());
        assertThat(vo.getReportStatus()).isEqualTo("PROCESSING");
        assertThat(vo.getOrderNo()).isEqualTo(ORDER_NO);
        assertThat(vo.getFinishedAt()).isNotNull();
    }

    @Test
    void finishRejectsNonOwnerWith3008() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.IN_SERVICE.getCode(), USER_ID, 999L));
        assertThatThrownBy(() -> service.finish(WORKER_ID, ORDER_NO, finishRequest()))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_BELONG_TO_USER));
        verify(traceService, never()).saveVouchers(any(), any(), any());
        verify(reportTaskPublisher, never()).publishReportTask(any());
    }

    @Test
    void finishOptimisticFailThrows3002AndSkipsPublish() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.IN_SERVICE.getCode(), USER_ID, WORKER_ID));
        when(orderMapper.update(any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.finish(WORKER_ID, ORDER_NO, finishRequest()))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_STATE_ILLEGAL));
        verify(reportTaskPublisher, never()).publishReportTask(any());
    }

    @Test
    void confirmSettlesAndBumpsCreditAndServiceCount() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.PENDING_CONFIRM.getCode(), USER_ID, WORKER_ID));
        when(orderMapper.update(any(), any())).thenReturn(1);

        service.confirm(USER_ID, ORDER_NO);

        verify(walletService).settle(eq(ORDER_NO), eq(USER_ID), eq(WORKER_ID), any());
        verify(creditService).change(WORKER_ID, CreditChangeType.ORDER_COMPLETED, ORDER_NO, null);
        verify(workerService).incrementServiceCount(WORKER_ID);
    }

    @Test
    void confirmRejectsNonOwnerWith1003() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.PENDING_CONFIRM.getCode(), 123L, WORKER_ID));
        assertThatThrownBy(() -> service.confirm(USER_ID, ORDER_NO))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void startRejectsNonOwnerWith3008() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.ACCEPTED.getCode(), USER_ID, 999L));
        assertThatThrownBy(() -> service.start(WORKER_ID, ORDER_NO))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_BELONG_TO_USER));
    }
}