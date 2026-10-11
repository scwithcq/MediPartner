package com.dsc.medipartner.module.order.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.infra.mq.ReportTaskPublisher;
import com.dsc.medipartner.module.demand.service.DemandService;
import com.dsc.medipartner.module.job.config.JobProperties;
import com.dsc.medipartner.module.order.domain.dto.OrderQuery;
import com.dsc.medipartner.module.order.domain.dto.PriceResult;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.domain.vo.ContactVO;
import com.dsc.medipartner.module.order.domain.vo.PricePreviewVO;
import com.dsc.medipartner.module.order.mapper.ServiceOrderMapper;
import com.dsc.medipartner.module.order.service.ActionResolver;
import com.dsc.medipartner.module.order.service.OrderAssembler;
import com.dsc.medipartner.module.order.service.OrderStateMachineService;
import com.dsc.medipartner.module.order.service.OrderStatusLogService;
import com.dsc.medipartner.module.order.service.PriceService;
import com.dsc.medipartner.module.order.service.TraceService;
import com.dsc.medipartner.module.order.service.VirtualPhoneService;
import com.dsc.medipartner.module.review.service.ReviewService;
import com.dsc.medipartner.module.ticket.service.AppealService;
import com.dsc.medipartner.module.user.domain.entity.SysUser;
import com.dsc.medipartner.module.user.service.CreditService;
import com.dsc.medipartner.module.user.service.UserService;
import com.dsc.medipartner.module.user.service.WorkerService;
import com.dsc.medipartner.module.wallet.service.WalletService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the no-migration interfaces: §5.1 price preview, §5.6/§7.10 contact,
 * §3.3/§7.1 status counting.
 */
class OrderOverviewAndContactTest {

    private static final long USER_ID = 9L;
    private static final long WORKER_ID = 7L;
    private static final String ORDER_NO = "MP20260921000000123456";

    private ServiceOrderMapper orderMapper;
    private PriceService priceService;
    private UserService userService;
    private OrderServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ServiceOrder.class);
    }

    @BeforeEach
    void setUp() {
        orderMapper = mock(ServiceOrderMapper.class);
        priceService = mock(PriceService.class);
        userService = mock(UserService.class);
        service = new OrderServiceImpl(orderMapper, mock(WalletService.class), mock(WorkerService.class),
                mock(OrderStateMachineService.class), mock(TraceService.class), mock(ReportTaskPublisher.class),
                mock(DemandService.class), mock(CreditService.class), priceService, userService,
                mock(ReviewService.class), new ActionResolver(), new OrderAssembler(),
                new VirtualPhoneService(), mock(OrderStatusLogService.class),
                mock(AppealService.class), new JobProperties());
    }

    private PriceResult priced() {
        PriceResult price = new PriceResult();
        price.setServiceType("ACCOMPANY");
        price.setServiceTypeName("全程陪诊");
        price.setCity("DEFAULT");
        price.setHours(new BigDecimal("4.0"));
        price.setBasePrice(new BigDecimal("200.00"));
        price.setExtraHours(new BigDecimal("0.0"));
        price.setExtraHourPrice(new BigDecimal("30.00"));
        price.setAmount(new BigDecimal("200.00"));
        price.setPriceRuleId(40001L);
        price.setPriceVersion("1");
        price.setRemark("全程陪诊 200.00 元含 4 小时");
        return price;
    }

    private ServiceOrder order(int status, Long ownerId, Long workerId) {
        ServiceOrder order = new ServiceOrder();
        order.setId(1L);
        order.setOrderNo(ORDER_NO);
        order.setUserId(ownerId);
        order.setWorkerId(workerId);
        order.setOrderStatus(status);
        order.setAmount(new BigDecimal("200.00"));
        return order;
    }

    private SysUser user(long id, String nickname, String phone) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setNickname(nickname);
        u.setPhone(phone);
        u.setRoleMask(3);
        return u;
    }

    // ---------------- §5.1 price preview ----------------

    @Test
    void pricePreviewMapsPriceServiceResult() {
        when(priceService.calc(any(), any(), any())).thenReturn(priced());

        PricePreviewVO vo = service.pricePreview("ACCOMPANY", null, new BigDecimal("4.0"));

        assertThat(vo.getServiceType()).isEqualTo("ACCOMPANY");
        assertThat(vo.getServiceTypeName()).isEqualTo("全程陪诊");
        assertThat(vo.getAmount()).isEqualByComparingTo("200.00");
        assertThat(vo.getPriceVersion()).isEqualTo("1");
        assertThat(vo.getPriceRuleId()).isEqualTo(40001L);
        assertThat(vo.getRemark()).isNotNull();
    }

    /** §5.1: the quote carries a validity deadline (default 30 minutes). */
    @Test
    void pricePreviewSetsValidUntilAboutThirtyMinutesAhead() {
        when(priceService.calc(any(), any(), any())).thenReturn(priced());

        PricePreviewVO vo = service.pricePreview("ACCOMPANY", null, null);

        assertThat(vo.getValidUntil()).isNotNull();
        assertThat(vo.getValidUntil()).isAfter(LocalDateTime.now().plusMinutes(25));
        assertThat(vo.getValidUntil()).isBefore(LocalDateTime.now().plusMinutes(35));
    }

    /** §5.1: an unconfigured service type must surface 3009, not silently price to zero. */
    @Test
    void pricePreviewPropagates3009() {
        when(priceService.calc(any(), any(), any()))
                .thenThrow(new BizException(ErrorCode.PRICE_RULE_NOT_FOUND));

        assertThatThrownBy(() -> service.pricePreview("NOT_EXIST", null, null))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRICE_RULE_NOT_FOUND));
    }

    // ---------------- §5.6 / §7.10 contact ----------------

    @Test
    void userContactMasksAndGeneratesVirtualPhone() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.ACCEPTED.getCode(), USER_ID, WORKER_ID));
        when(userService.getById(WORKER_ID)).thenReturn(user(WORKER_ID, "李四", "13800001234"));

        ContactVO vo = service.userContact(USER_ID, ORDER_NO);

        assertThat(vo.getPeerRole()).isEqualTo("WORKER");
        assertThat(vo.getMaskedPhone()).isEqualTo("138****1234");
        assertThat(vo.getPeerName()).isEqualTo("李*");
        assertThat(vo.getVirtualPhone()).startsWith("170");
        assertThat(vo.getCanCall()).isTrue();
        assertThat(vo.getTip()).isNotBlank();
    }

    /** Both sides must see the SAME virtual number for one order (privacy-number semantics). */
    @Test
    void bothSidesGetSameVirtualPhone() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.IN_SERVICE.getCode(), USER_ID, WORKER_ID));
        when(userService.getById(WORKER_ID)).thenReturn(user(WORKER_ID, "李四", "13800001234"));
        when(userService.getById(USER_ID)).thenReturn(user(USER_ID, "王五", "13900005678"));

        ContactVO fromUser = service.userContact(USER_ID, ORDER_NO);
        ContactVO fromWorker = service.workerContact(WORKER_ID, ORDER_NO);

        assertThat(fromUser.getVirtualPhone()).isEqualTo(fromWorker.getVirtualPhone());
        assertThat(fromUser.getPeerRole()).isEqualTo("WORKER");
        assertThat(fromWorker.getPeerRole()).isEqualTo("USER");
        // 双方看到的是对方的脱敏号，不是自己的
        assertThat(fromUser.getMaskedPhone()).isEqualTo("138****1234");
        assertThat(fromWorker.getMaskedPhone()).isEqualTo("139****5678");
    }

    @Test
    void contactRejectsNonOwnerWith3008() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.ACCEPTED.getCode(), 123L, WORKER_ID));

        assertThatThrownBy(() -> service.userContact(USER_ID, ORDER_NO))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_BELONG_TO_USER));
    }

    /** Not yet accepted: there is no counterpart to contact (3002). */
    @Test
    void contactRejectedWhenNotYetAccepted() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.WAITING_ACCEPT.getCode(), USER_ID, null));

        assertThatThrownBy(() -> service.userContact(USER_ID, ORDER_NO))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_STATE_ILLEGAL));
    }

    /** §5.6: canCall is true only within states 20~50. */
    @Test
    void canCallFollowsOrderState() {
        when(userService.getById(WORKER_ID)).thenReturn(user(WORKER_ID, "李四", "13800001234"));
        when(userService.getById(USER_ID)).thenReturn(user(USER_ID, "王五", "13900005678"));

        assertThat(contactWithStatus(OrderState.ACCEPTED.getCode()).getCanCall()).isTrue();
        assertThat(contactWithStatus(OrderState.IN_SERVICE.getCode()).getCanCall()).isTrue();
        assertThat(contactWithStatus(OrderState.PENDING_CONFIRM.getCode()).getCanCall()).isTrue();
        assertThat(contactWithStatus(OrderState.COMPLETED.getCode()).getCanCall()).isTrue();
        assertThat(contactWithStatus(OrderState.CANCELLED.getCode()).getCanCall()).isFalse();
        assertThat(contactWithStatus(OrderState.ABNORMAL.getCode()).getCanCall()).isFalse();
    }

    private ContactVO contactWithStatus(int status) {
        when(orderMapper.selectOne(any())).thenReturn(order(status, USER_ID, WORKER_ID));
        return service.userContact(USER_ID, ORDER_NO);
    }

    // ---------------- §3.3 status counting ----------------

    /** §3.3: every status key must exist even with no orders, so the client needs no null checks. */
    @Test
    void statusCountsAlwaysContainAllKeys() {
        when(orderMapper.selectList(any())).thenReturn(List.of());

        Map<String, Long> counts = service.countByStatusForUser(USER_ID);

        assertThat(counts).containsOnlyKeys("waitingAccept", "accepted", "inService",
                "pendingConfirm", "completed", "cancelled", "abnormal");
        assertThat(counts.values()).allMatch(v -> v == 0L);
    }

    @Test
    void statusCountsTallyByState() {
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order(OrderState.WAITING_ACCEPT.getCode(), USER_ID, null),
                order(OrderState.WAITING_ACCEPT.getCode(), USER_ID, null),
                order(OrderState.IN_SERVICE.getCode(), USER_ID, WORKER_ID),
                order(OrderState.COMPLETED.getCode(), USER_ID, WORKER_ID)));

        Map<String, Long> counts = service.countByStatusForUser(USER_ID);

        assertThat(counts.get("waitingAccept")).isEqualTo(2L);
        assertThat(counts.get("inService")).isEqualTo(1L);
        assertThat(counts.get("completed")).isEqualTo(1L);
        assertThat(counts.get("cancelled")).isEqualTo(0L);
    }

    /** Dirty data with an unknown status code must not break the tally. */
    @Test
    void statusCountsIgnoreUnknownStatusCodes() {
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order(999, USER_ID, null),
                order(OrderState.COMPLETED.getCode(), USER_ID, WORKER_ID)));

        Map<String, Long> counts = service.countByStatusForUser(USER_ID);

        assertThat(counts.get("completed")).isEqualTo(1L);
        assertThat(counts.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(1L);
    }

    // ---------------- §7.1 counters ----------------

    @Test
    void countHallCountsWaitingAcceptOrders() {
        when(orderMapper.selectCount(any())).thenReturn(5L);
        assertThat(service.countHall()).isEqualTo(5L);
    }

    @Test
    void countTodayPendingAndMonthCompletedDelegateToMapper() {
        when(orderMapper.selectCount(any())).thenReturn(3L);
        assertThat(service.countTodayPending(WORKER_ID)).isEqualTo(3L);
        assertThat(service.countMonthCompleted(WORKER_ID)).isEqualTo(3L);
    }
}
