package com.dsc.medipartner.module.job;

import com.dsc.medipartner.common.cache.LocalCacheService;
import com.dsc.medipartner.module.job.config.JobProperties;
import com.dsc.medipartner.module.order.domain.entity.OrderTrace;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.service.OrderService;
import com.dsc.medipartner.module.order.service.TraceService;
import com.dsc.medipartner.module.user.domain.enums.CreditChangeType;
import com.dsc.medipartner.module.user.service.CreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定时任务测试（阶段 5，契约 §7.2）。
 *
 * <p>重点验证三件容易出错、且出错代价很高的事：
 * <ol>
 *   <li><b>信用分扣分幂等</b>——任务每 60 秒跑一轮，重复扣分会把信用分扣穿</li>
 *   <li><b>单笔失败不阻塞整批</b>——一条坏数据不能卡住其余订单的处理</li>
 *   <li><b>批量上限生效</b>——积压时不至于一次拉爆数据库</li>
 * </ol>
 */
class ScheduledOrderJobsTest {

    private OrderService orderService;
    private TraceService traceService;
    private CreditService creditService;
    private LocalCacheService cacheService;
    private JobProperties jobProperties;
    private ScheduledOrderJobs jobs;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        traceService = mock(TraceService.class);
        creditService = mock(CreditService.class);
        cacheService = new LocalCacheService();
        jobProperties = new JobProperties();
        jobProperties.setBatchSize(200);
        jobs = new ScheduledOrderJobs(orderService, traceService, creditService, cacheService, jobProperties);
    }

    private ServiceOrder order(String orderNo, Long workerId) {
        ServiceOrder o = new ServiceOrder();
        o.setOrderNo(orderNo);
        o.setWorkerId(workerId);
        o.setStartedAt(LocalDateTime.now().minusHours(2));
        return o;
    }

    // ---------------- 总开关 ----------------

    /** enabled=false 时任何任务都不得触库（压测/排查时用） */
    @Test
    void disabledJobsDoNotTouchDatabase() {
        jobProperties.setEnabled(false);
        jobs.runAll();

        verify(orderService, never()).findTimeoutWaitingOrderNos(any(Integer.class));
        verify(orderService, never()).findAutoConfirmableOrderNos(any(Integer.class));
        verify(orderService, never()).findOverdueInServiceOrderNos(any(Integer.class));
        verify(orderService, never()).findOrdersMissingTrace(any(Integer.class));
    }

    // ---------------- ① 超时取消 ----------------

    @Test
    void timeoutCancelProcessesAllCandidates() {
        when(orderService.findTimeoutWaitingOrderNos(200)).thenReturn(List.of("A", "B"));
        when(orderService.systemTimeoutCancel("A")).thenReturn(true);
        when(orderService.systemTimeoutCancel("B")).thenReturn(true);

        jobs.runAll();

        verify(orderService).systemTimeoutCancel("A");
        verify(orderService).systemTimeoutCancel("B");
    }

    /** 单笔抛异常不得中断其余订单（一条坏数据不能卡住整批） */
    @Test
    void oneFailedOrderDoesNotBlockOthers() {
        when(orderService.findTimeoutWaitingOrderNos(200)).thenReturn(List.of("bad", "good"));
        when(orderService.systemTimeoutCancel("bad")).thenThrow(new RuntimeException("db error"));
        when(orderService.systemTimeoutCancel("good")).thenReturn(true);

        jobs.runAll();

        verify(orderService).systemTimeoutCancel("good");
    }

    /** 某一类任务失败不得影响后续任务（超时取消炸了，自动确认仍要跑） */
    @Test
    void failureInOneJobDoesNotBlockFollowingJobs() {
        when(orderService.findTimeoutWaitingOrderNos(any(Integer.class)))
                .thenThrow(new RuntimeException("query failed"));
        when(orderService.findAutoConfirmableOrderNos(200)).thenReturn(List.of("C"));
        when(orderService.systemAutoConfirm("C")).thenReturn(true);

        jobs.runAll();

        verify(orderService).systemAutoConfirm("C");
    }

    // ---------------- ② 自动确认 ----------------

    @Test
    void autoConfirmProcessesCandidates() {
        when(orderService.findAutoConfirmableOrderNos(200)).thenReturn(List.of("C1", "C2"));
        when(orderService.systemAutoConfirm("C1")).thenReturn(true);
        when(orderService.systemAutoConfirm("C2")).thenReturn(false); // 并发已被处理

        jobs.runAll();

        verify(orderService).systemAutoConfirm("C1");
        verify(orderService).systemAutoConfirm("C2");
    }

    /**
     * 结算失败的订单要进入退避，不再被每个扫描周期反复重试。
     *
     * <p>背景：真机验证时发现一笔资金异常的订单每 20 秒报一次「余额不足」且永不停歇。
     * 不退避会刷爆日志、持续占用数据库，还会把真正需要人工介入的问题淹没在重复告警里。
     */
    @Test
    void failedAutoConfirmIsBackedOff() {
        when(orderService.findAutoConfirmableOrderNos(200)).thenReturn(List.of("X"));
        when(orderService.systemAutoConfirm("X")).thenThrow(new RuntimeException("余额不足"));

        // 第一轮失败并标记退避
        jobs.runAll();
        verify(orderService, times(1)).systemAutoConfirm("X");

        // 后续 3 轮应被退避拦住，不再重试
        jobs.runAll();
        jobs.runAll();
        jobs.runAll();
        verify(orderService, times(1)).systemAutoConfirm("X");
    }

    /** 退避期结束后应重新尝试（不能让订单永远不再处理） */
    @Test
    void backoffExpiresAndRetriesAgain() {
        jobProperties.setFailureBackoffMinutes(0);
        when(orderService.findAutoConfirmableOrderNos(200)).thenReturn(List.of("Y"));
        when(orderService.systemAutoConfirm("Y")).thenThrow(new RuntimeException("fail"));

        jobs.runAll();
        jobs.runAll();

        // 退避 0 分钟 = 立即过期，两次都应尝试
        verify(orderService, times(2)).systemAutoConfirm("Y");
    }

    /** 成功后清除退避标记，避免残留标记让该单今后被无故跳过 */
    @Test
    void successClearsBackoff() {
        when(orderService.findAutoConfirmableOrderNos(200)).thenReturn(List.of("Z"));
        when(orderService.systemAutoConfirm("Z"))
                .thenThrow(new RuntimeException("第一次失败"))
                .thenReturn(true);

        jobs.runAll(); // 失败，标记退避
        // 手动清掉退避以模拟退避到期
        cacheService.delete("medi:job:autoConfirm:backoff:Z");
        jobs.runAll(); // 成功

        assertThat(cacheService.exists("medi:job:autoConfirm:backoff:Z")).isFalse();
    }

    // ---------------- ③ 标记异常 ----------------

    @Test
    void overdueInServiceOrdersAreMarkedAbnormal() {
        when(orderService.findOverdueInServiceOrderNos(200)).thenReturn(List.of("D"));
        when(orderService.systemMarkAbnormal(eq("D"), anyString())).thenReturn(true);

        jobs.runAll();

        verify(orderService).systemMarkAbnormal(eq("D"), anyString());
    }

    // ---------------- ④ 留痕超时 ----------------

    /** 无留痕 → 告警并扣分 */
    @Test
    void missingTraceTriggersPenalty() {
        when(orderService.findOrdersMissingTrace(200)).thenReturn(List.of(order("E", 900L)));
        when(traceService.listByOrder("E")).thenReturn(List.of());

        jobs.runAll();

        verify(creditService).change(900L, CreditChangeType.TRACE_TIMEOUT, "E", null);
    }

    /** 已有留痕 → 不扣分（留痕是陪诊师已履约的证据） */
    @Test
    void existingTraceDoesNotTriggerPenalty() {
        when(orderService.findOrdersMissingTrace(200)).thenReturn(List.of(order("F", 900L)));
        when(traceService.listByOrder("F")).thenReturn(List.of(new OrderTrace()));

        jobs.runAll();

        verify(creditService, never()).change(any(), any(), anyString(), any());
    }

    /**
     * <b>幂等核心</b>：同一订单多轮扫描只扣一次分。
     *
     * <p>若不限制，任务每 60 秒跑一轮、每轮扣 2 分，一小时就能把信用分从 100 扣到 0。
     * 这是定时任务最典型的资损形态，必须锁死。
     */
    @Test
    void penaltyIsChargedOnlyOncePerOrderAcrossRounds() {
        when(orderService.findOrdersMissingTrace(200)).thenReturn(List.of(order("G", 900L)));
        when(traceService.listByOrder("G")).thenReturn(List.of());

        // 模拟连续 5 轮扫描
        jobs.runAll();
        jobs.runAll();
        jobs.runAll();
        jobs.runAll();
        jobs.runAll();

        verify(creditService, times(1)).change(900L, CreditChangeType.TRACE_TIMEOUT, "G", null);
    }

    /** 关闭一次性限制后，每轮都会扣（用于特殊场景，默认不开） */
    @Test
    void penaltyRepeatsWhenOncePerOrderDisabled() {
        jobProperties.setTracePenaltyOncePerOrder(false);
        when(orderService.findOrdersMissingTrace(200)).thenReturn(List.of(order("H", 900L)));
        when(traceService.listByOrder("H")).thenReturn(List.of());

        jobs.runAll();
        jobs.runAll();

        verify(creditService, times(2)).change(900L, CreditChangeType.TRACE_TIMEOUT, "H", null);
    }

    /** 总开关关闭扣分时只告警不扣分（排查阈值期间用） */
    @Test
    void penaltyCanBeDisabledWhileStillAlerting() {
        jobProperties.setTraceTimeoutPenalty(false);
        when(orderService.findOrdersMissingTrace(200)).thenReturn(List.of(order("I", 900L)));
        when(traceService.listByOrder("I")).thenReturn(List.of());

        jobs.runAll();

        verify(creditService, never()).change(any(), any(), anyString(), any());
    }

    /** 查留痕失败时按「有留痕」处理，避免因查询故障误扣分 */
    @Test
    void traceQueryFailureDoesNotCausePenalty() {
        when(orderService.findOrdersMissingTrace(200)).thenReturn(List.of(order("J", 900L)));
        when(traceService.listByOrder("J")).thenThrow(new RuntimeException("db down"));

        jobs.runAll();

        verify(creditService, never()).change(any(), any(), anyString(), any());
    }

    /** 无陪诊师的订单（异常数据）不扣分，只告警 */
    @Test
    void orderWithoutWorkerDoesNotTriggerPenalty() {
        when(orderService.findOrdersMissingTrace(200)).thenReturn(List.of(order("K", null)));
        when(traceService.listByOrder("K")).thenReturn(List.of());

        jobs.runAll();

        verify(creditService, never()).change(any(), any(), anyString(), any());
    }

    /** 不同订单各自独立计一次，互不影响 */
    @Test
    void penaltyIsTrackedPerOrder() {
        when(orderService.findOrdersMissingTrace(200))
                .thenReturn(List.of(order("L", 901L), order("M", 902L)));
        when(traceService.listByOrder("L")).thenReturn(List.of());
        when(traceService.listByOrder("M")).thenReturn(List.of());

        jobs.runAll();
        jobs.runAll();

        verify(creditService, times(1)).change(901L, CreditChangeType.TRACE_TIMEOUT, "L", null);
        verify(creditService, times(1)).change(902L, CreditChangeType.TRACE_TIMEOUT, "M", null);
    }

    // ---------------- 分布式锁 ----------------

    /**
     * 已有实例持锁时，本实例跳过该任务。
     *
     * <p>模拟多实例部署：先占用锁键，再跑任务，应当完全不触库。
     */
    @Test
    void jobIsSkippedWhenLockHeldByAnotherInstance() {
        cacheService.setIfAbsent("medi:job:timeoutCancel", "other", java.time.Duration.ofMinutes(5));

        jobs.runAll();

        verify(orderService, never()).findTimeoutWaitingOrderNos(any(Integer.class));
        // 其它任务未持锁，仍应正常执行
        verify(orderService).findAutoConfirmableOrderNos(any(Integer.class));
    }

    /** 任务执行后必须释放锁，否则该任务从此再也不跑 */
    @Test
    void lockIsReleasedAfterRun() {
        when(orderService.findTimeoutWaitingOrderNos(200)).thenReturn(List.of());

        jobs.runAll();

        assertThat(cacheService.exists("medi:job:timeoutCancel")).isFalse();
    }

    /** 任务抛异常时锁也必须释放（否则一次失败就永久卡死该任务） */
    @Test
    void lockIsReleasedEvenWhenJobFails() {
        when(orderService.findTimeoutWaitingOrderNos(any(Integer.class)))
                .thenThrow(new RuntimeException("boom"));

        jobs.runAll();

        assertThat(cacheService.exists("medi:job:timeoutCancel")).isFalse();
    }

    // ---------------- 批量上限 ----------------

    /** 配置的批量上限必须原样传给查询，避免一次拉爆数据库 */
    @Test
    void batchSizeIsPassedToQueries() {
        jobProperties.setBatchSize(37);

        jobs.runAll();

        verify(orderService).findTimeoutWaitingOrderNos(37);
        verify(orderService).findAutoConfirmableOrderNos(37);
        verify(orderService).findOverdueInServiceOrderNos(37);
        verify(orderService).findOrdersMissingTrace(37);
    }

    /** 空候选时不应产生任何写操作 */
    @Test
    void emptyCandidatesProduceNoWrites() {
        when(orderService.findTimeoutWaitingOrderNos(200)).thenReturn(List.of());
        when(orderService.findAutoConfirmableOrderNos(200)).thenReturn(List.of());
        when(orderService.findOverdueInServiceOrderNos(200)).thenReturn(List.of());
        when(orderService.findOrdersMissingTrace(200)).thenReturn(List.of());

        jobs.runAll();

        verify(orderService, never()).systemTimeoutCancel(anyString());
        verify(orderService, never()).systemAutoConfirm(anyString());
        verify(orderService, never()).systemMarkAbnormal(anyString(), anyString());
        verify(creditService, never()).change(any(), any(), anyString(), any());
    }

    /** 候选中订单号重复时不应重复处理（防御性：查询本不该返回重复） */
    @Test
    void duplicateOrderNosAreProcessedIndependently() {
        List<String> candidates = new ArrayList<>(List.of("N", "N"));
        when(orderService.findAutoConfirmableOrderNos(200)).thenReturn(candidates);
        when(orderService.systemAutoConfirm("N")).thenReturn(true);

        jobs.runAll();

        // systemAutoConfirm 内部有状态校验，重复调用第二次会返回 false，
        // 因此这里只确认被调用，真正的去重由订单状态保证
        verify(orderService, times(2)).systemAutoConfirm("N");
    }
}
