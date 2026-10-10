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
import com.dsc.medipartner.module.order.domain.dto.PriceResult;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.domain.vo.FinishResultVO;
import com.dsc.medipartner.module.order.mapper.ServiceOrderMapper;
import com.dsc.medipartner.module.order.service.ActionResolver;
import com.dsc.medipartner.module.order.service.OrderAssembler;
import com.dsc.medipartner.module.order.service.OrderStateMachineService;
import com.dsc.medipartner.module.order.service.PriceService;
import com.dsc.medipartner.module.order.service.TraceService;
import com.dsc.medipartner.module.order.service.VirtualPhoneService;
import com.dsc.medipartner.module.review.service.ReviewService;
import com.dsc.medipartner.module.user.domain.enums.CreditChangeType;
import com.dsc.medipartner.module.user.service.CreditService;
import com.dsc.medipartner.module.user.service.UserService;
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
 * OrderServiceImpl unit tests: server-side pricing on create (H-1), voucher persistence and
 * report publishing on finish, settlement plus credit bump on confirm.
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
    private PriceService priceService;
    private UserService userService;
    private ReviewService reviewService;
    private ActionResolver actionResolver;
    private OrderAssembler orderAssembler;
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
        priceService = mock(PriceService.class);
        userService = mock(UserService.class);
        reviewService = mock(ReviewService.class);
        actionResolver = new ActionResolver();
        orderAssembler = new OrderAssembler();
        service = new OrderServiceImpl(orderMapper, walletService, workerService, stateMachineService,
                traceService, reportTaskPublisher, demandService, creditService, priceService,
                userService, reviewService, actionResolver, orderAssembler, new VirtualPhoneService());
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

    /** After H-1 the create request no longer carries an amount; pricing is always server side. */
    private CreateOrderRequest createRequest(Long demandId) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setServiceType("ACCOMPANY");
        request.setSymptomDesc("chest pain");
        request.setCity("Chongqing");
        request.setHospitalPref("Hospital A");
        request.setServiceTime(LocalDateTime.now().plusDays(1));
        request.setDemandId(demandId);
        return request;
    }

    /** Stubbed server-side price result */
    private PriceResult pricedAmount(String amount) {
        PriceResult result = new PriceResult();
        result.setServiceType("ACCOMPANY");
        result.setServiceTypeName("ACCOMPANY");
        result.setCity("DEFAULT");
        result.setHours(new BigDecimal("4.0"));
        result.setBasePrice(new BigDecimal("200.00"));
        result.setExtraHours(BigDecimal.ZERO.setScale(1));
        result.setExtraHourPrice(new BigDecimal("30.00"));
        result.setAmount(new BigDecimal(amount));
        result.setPriceRuleId(40001L);
        result.setPriceVersion("1");
        return result;
    }

    private void stubPrice() {
        when(priceService.calc(any(), any(), any())).thenReturn(pricedAmount("200.00"));
    }

    private FinishOrderRequest finishRequest() {
        FinishOrderRequest request = new FinishOrderRequest();
        request.setVoucherUrls(List.of("/files/ORDER_VOUCHER/1.jpg"));
        request.setGpsLat("29.56");
        request.setGpsLng("106.55");
        request.setWatermarkTime(LocalDateTime.now().format(TS));
        request.setServiceSummary("done");
        request.setActualHours(new BigDecimal("2.0"));
        return request;
    }

    @Test
    void createSetsDemandIdAndMarksOrdered() {
        login(USER_ID);
        stubPrice();
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

    /** H-1 core regression: amount comes from PriceService, never from the client payload. */
    @Test
    void createUsesServerSidePriceNotClientValue() {
        login(USER_ID);
        stubPrice();
        service.create(createRequest(null));

        ArgumentCaptor<ServiceOrder> captor = ArgumentCaptor.forClass(ServiceOrder.class);
        verify(orderMapper).insert(captor.capture());
        ServiceOrder inserted = captor.getValue();
        assertThat(inserted.getAmount()).isEqualByComparingTo("200.00");
        assertThat(inserted.getPriceVersion()).isEqualTo("1");
        assertThat(inserted.getPriceRuleId()).isEqualTo(40001L);
        verify(walletService).freeze(eq(USER_ID), any(), eq(new BigDecimal("200.00")));
    }

    /** Client-displayed amount disagreeing with the recomputed price -&gt; 3010, no freeze, no insert. */
    @Test
    void createRejectsExpectAmountMismatchWith3010() {
        login(USER_ID);
        stubPrice();
        CreateOrderRequest request = createRequest(null);
        request.setExpectAmount(new BigDecimal("0.01"));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRICE_CHANGED));
        verify(walletService, never()).freeze(any(), any(), any());
        verify(orderMapper, never()).insert(any(ServiceOrder.class));
    }

    /** Stale priceVersion from the client -&gt; 3010. */
    @Test
    void createRejectsStalePriceVersionWith3010() {
        login(USER_ID);
        stubPrice();
        CreateOrderRequest request = createRequest(null);
        request.setPriceVersion("0");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRICE_CHANGED));
        verify(walletService, never()).freeze(any(), any(), any());
    }

    /** Service time less than 30 minutes ahead -&gt; 3007. */
    @Test
    void createRejectsTooEarlyServiceTimeWith3007() {
        login(USER_ID);
        stubPrice();
        CreateOrderRequest request = createRequest(null);
        request.setServiceTime(LocalDateTime.now().plusMinutes(5));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_TIME_ILLEGAL));
        verify(walletService, never()).freeze(any(), any(), any());
    }

    /** Service time more than 30 days ahead -&gt; 3007. */
    @Test
    void createRejectsTooFarServiceTimeWith3007() {
        login(USER_ID);
        stubPrice();
        CreateOrderRequest request = createRequest(null);
        request.setServiceTime(LocalDateTime.now().plusDays(31));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_TIME_ILLEGAL));
    }

    @Test
    void createWithoutDemandIdSkipsMarkOrdered() {
        login(USER_ID);
        stubPrice();
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
