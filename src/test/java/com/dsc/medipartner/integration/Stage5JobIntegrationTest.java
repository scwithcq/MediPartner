package com.dsc.medipartner.integration;

import com.dsc.medipartner.module.job.ScheduledOrderJobs;
import com.dsc.medipartner.module.job.config.JobProperties;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 5 定时任务集成测试：连<b>真实 MySQL</b>，验证「查候选 → 流转 → 资金」整条链路。
 *
 * <p>单测用 mock 覆盖了分支逻辑，但有一条关键路径只有真库能验：
 * <b>{@code findAutoConfirmableOrderNos} 的 SQL 是否正确排除了被申诉暂停的单</b>。
 * 这段逻辑依赖 {@code appeal_ticket.auto_confirm_paused} 与订单表的配合，
 * mock 掉 Mapper 后等于没验证——而它一旦失效就是「仲裁未结论、款项已结算」的资损。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
@DisplayName("阶段5 定时任务（真实 MySQL）")
class Stage5JobIntegrationTest {

    /** V4 种子：求助者（有余额）、陪诊师（已过审，信用分 100） */
    private static final long USER_ID = 10002L;
    private static final long WORKER_ID = 10003L;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ScheduledOrderJobs scheduledOrderJobs;

    @Autowired
    private JobProperties jobProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 造一条指定状态的订单，各时间字段按需回拨以触发超时判定。
     *
     * <p><b>为什么必须同时冻结资金</b>：真实下单会 {@code freeze} 服务费，
     * 后续「超时取消」要 {@code unfreeze}、「自动确认」要 {@code settle}。
     * 若只裸插订单行而不冻结，取消会因「钱包账户不存在 / 余额不足」失败——
     * 这不是缺陷，而是服务层正确地拒绝了「退还从未冻结过的钱」。
     */
    private String insertOrder(int status, LocalDateTime createdAt, LocalDateTime startedAt,
                               LocalDateTime finishedAt, Long workerId) {
        String orderNo = "MPJOB" + System.nanoTime();
        jdbcTemplate.update(
                "INSERT INTO service_order (id, order_no, user_id, worker_id, amount, order_status, "
                        + "service_type, city, city_code, created_at, updated_at, started_at, finished_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'ACCOMPANY', '广州市', '440100', ?, NOW(), ?, ?, 0)",
                System.nanoTime(), orderNo, USER_ID, workerId,
                new BigDecimal("200.00"), status, createdAt, startedAt, finishedAt);
        freezeForOrder(orderNo, USER_ID, new BigDecimal("200.00"));
        return orderNo;
    }

    /**
     * 复现下单时的资金冻结：balance 扣减、frozen 增加，并写 PAY 流水。
     *
     * <p>字段与 {@code WalletServiceImpl.record(...)} 保持一致（含 balance_before / frozen_* 与
     * {@code biz_no} 幂等键），否则后续结算的幂等守卫会因流水形态不符而误判。
     */
    private void freezeForOrder(String orderNo, long userId, BigDecimal amount) {
        ensureWallet(userId);
        BigDecimal before = jdbcTemplate.queryForObject(
                "SELECT balance FROM wallet_account WHERE user_id = ?", BigDecimal.class, userId);
        BigDecimal frozenBefore = jdbcTemplate.queryForObject(
                "SELECT frozen FROM wallet_account WHERE user_id = ?", BigDecimal.class, userId);

        jdbcTemplate.update(
                "UPDATE wallet_account SET balance = balance - ?, frozen = frozen + ? WHERE user_id = ?",
                amount, amount, userId);
        jdbcTemplate.update(
                "INSERT INTO wallet_transaction (id, txn_no, user_id, order_no, biz_no, type, amount, "
                        + "balance_before, balance_after, frozen_before, frozen_after, remark, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'PAY', ?, ?, ?, ?, ?, ?, NOW())",
                System.nanoTime(), "TXPREP" + System.nanoTime(), userId, orderNo, orderNo,
                amount.negate(), before, before.subtract(amount), frozenBefore, frozenBefore.add(amount),
                "订单冻结 " + orderNo);
    }

    /**
     * 确保钱包账户存在且余额足够。
     *
     * <p>结算要把钱打给陪诊师；集成测试库跨用例累积，余额可能被前序用例消耗。
     * 这里按需补足，避免用例因「余额不足」这种与定时任务无关的原因失败而变成 flaky。
     */
    private void ensureWallet(long userId) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_account WHERE user_id = ?", Integer.class, userId);
        if (exists == null || exists == 0) {
            jdbcTemplate.update(
                    "INSERT INTO wallet_account (id, user_id, balance, frozen, withdrawing, total_income, "
                            + "total_recharge, total_expense, version, created_at, updated_at) "
                            + "VALUES (?, ?, 1000.00, 0.00, 0.00, 0.00, 0.00, 0.00, 0, NOW(), NOW())",
                    System.nanoTime(), userId);
        }
        jdbcTemplate.update(
                "UPDATE wallet_account SET balance = 500.00, frozen = 0.00 "
                        + "WHERE user_id = ? AND balance < 500.00", userId);
    }

    // ---------------- ① 超时未接单 ----------------

    @Test
    @DisplayName("超时未接单：被查为候选并自动取消（资金解冻）")
    void timeoutWaitingOrderIsCancelled() {
        LocalDateTime created = LocalDateTime.now()
                .minusMinutes(jobProperties.getTimeoutCancelMinutes() + 10);
        String orderNo = insertOrder(OrderState.WAITING_ACCEPT.getCode(), created, null, null, null);

        assertThat(orderService.findTimeoutWaitingOrderNos(500)).contains(orderNo);

        BigDecimal frozenBefore = jdbcTemplate.queryForObject(
                "SELECT frozen FROM wallet_account WHERE user_id = ?", BigDecimal.class, USER_ID);

        assertThat(orderService.systemTimeoutCancel(orderNo)).isTrue();

        ServiceOrder after = orderService.getByOrderNo(orderNo);
        assertThat(after.getOrderStatus()).isEqualTo(OrderState.CANCELLED.getCode());
        assertThat(after.getCancelReason()).contains("超时");

        // 资金必须解冻，否则用户的钱永久锁死在冻结态
        BigDecimal frozenAfter = jdbcTemplate.queryForObject(
                "SELECT frozen FROM wallet_account WHERE user_id = ?", BigDecimal.class, USER_ID);
        assertThat(frozenAfter).isLessThan(frozenBefore);
    }

    /** 未达阈值的订单不得被选中（避免把刚下的单误取消） */
    @Test
    @DisplayName("未达超时阈值的订单不会成为候选")
    void freshOrderIsNotCandidate() {
        String orderNo = insertOrder(OrderState.WAITING_ACCEPT.getCode(),
                LocalDateTime.now(), null, null, null);
        assertThat(orderService.findTimeoutWaitingOrderNos(500)).doesNotContain(orderNo);
    }

    /** 已被接走的订单不再被选中 */
    @Test
    @DisplayName("已接单的订单不会成为超时取消候选")
    void acceptedOrderIsNotTimeoutCandidate() {
        LocalDateTime created = LocalDateTime.now()
                .minusMinutes(jobProperties.getTimeoutCancelMinutes() + 10);
        String orderNo = insertOrder(OrderState.ACCEPTED.getCode(), created, null, null, WORKER_ID);
        assertThat(orderService.findTimeoutWaitingOrderNos(500)).doesNotContain(orderNo);
    }

    /** 状态不符时系统方法返回 false 而非抛异常（并发下属正常，不是错误） */
    @Test
    @DisplayName("系统流转对状态不符的订单返回 false 而不抛异常")
    void systemTransitionOnWrongStateReturnsFalse() {
        String orderNo = insertOrder(OrderState.COMPLETED.getCode(),
                LocalDateTime.now(), null, null, WORKER_ID);
        assertThat(orderService.systemTimeoutCancel(orderNo)).isFalse();
        assertThat(orderService.systemAutoConfirm(orderNo)).isFalse();
        assertThat(orderService.systemMarkAbnormal(orderNo, "test")).isFalse();
    }

    @Test
    @DisplayName("不存在的订单返回 false")
    void missingOrderReturnsFalse() {
        assertThat(orderService.systemTimeoutCancel("MPJOB-NOT-EXIST")).isFalse();
        assertThat(orderService.systemAutoConfirm("MPJOB-NOT-EXIST")).isFalse();
    }

    // ---------------- ② 申诉暂停自动确认（关键资损防线） ----------------

    /**
     * <b>本类最重要的一条</b>：被申诉暂停自动确认的订单必须从候选中排除。
     *
     * <p>若这条失效：用户申诉后 48h 一到系统仍会自动结算给陪诊师，
     * 等仲裁判定该退款时钱已进对方账户——真实资损且无法单方面追回。
     */
    @Test
    @DisplayName("被申诉暂停自动确认的订单必须被排除在候选之外")
    void appealPausedOrderIsExcludedFromAutoConfirm() {
        LocalDateTime finished = LocalDateTime.now()
                .minusMinutes(jobProperties.getAutoConfirmMinutes() + 10);
        String orderNo = insertOrder(OrderState.PENDING_CONFIRM.getCode(),
                LocalDateTime.now().minusDays(3), null, finished, WORKER_ID);

        // 未申诉时应在候选内（证明前置条件成立，否则本用例会因「本来就不在候选里」而假通过）
        assertThat(orderService.findAutoConfirmableOrderNos(500)).contains(orderNo);

        // 模拟阶段 4 的申诉：落 auto_confirm_paused = 1
        jdbcTemplate.update(
                "INSERT INTO appeal_ticket (id, appeal_no, order_no, applicant_id, applicant_role, "
                        + "appeal_type, reason, status, auto_confirm_paused, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'USER', 'REFUND', '对服务不满意', 'PENDING', 1, NOW(), NOW())",
                System.nanoTime(), "APJOB" + System.nanoTime(), orderNo, USER_ID);

        // 关键断言：必须被排除
        assertThat(orderService.findAutoConfirmableOrderNos(500)).doesNotContain(orderNo);
    }

    /** 暂停标记为 0 的申诉（已结案）不应排除该订单 */
    @Test
    @DisplayName("auto_confirm_paused=0 的申诉不排除该订单")
    void appealWithoutPauseFlagDoesNotExclude() {
        LocalDateTime finished = LocalDateTime.now()
                .minusMinutes(jobProperties.getAutoConfirmMinutes() + 10);
        String orderNo = insertOrder(OrderState.PENDING_CONFIRM.getCode(),
                LocalDateTime.now().minusDays(3), null, finished, WORKER_ID);

        jdbcTemplate.update(
                "INSERT INTO appeal_ticket (id, appeal_no, order_no, applicant_id, applicant_role, "
                        + "appeal_type, reason, status, auto_confirm_paused, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'USER', 'REFUND', '已结案', 'CLOSED', 0, NOW(), NOW())",
                System.nanoTime(), "APJOB" + System.nanoTime(), orderNo, USER_ID);

        assertThat(orderService.findAutoConfirmableOrderNos(500)).contains(orderNo);
    }

    // ---------------- ③ 服务中超时 ----------------

    @Test
    @DisplayName("服务中超时订单被查为异常候选并标记异常")
    void overdueInServiceIsMarkedAbnormal() {
        LocalDateTime started = LocalDateTime.now()
                .minusMinutes(jobProperties.getAbnormalMinutes() + 10);
        String orderNo = insertOrder(OrderState.IN_SERVICE.getCode(),
                LocalDateTime.now().minusDays(2), started, null, WORKER_ID);

        assertThat(orderService.findOverdueInServiceOrderNos(500)).contains(orderNo);
        assertThat(orderService.systemMarkAbnormal(orderNo, "服务超时未结束")).isTrue();

        ServiceOrder after = orderService.getByOrderNo(orderNo);
        assertThat(after.getOrderStatus()).isEqualTo(OrderState.ABNORMAL.getCode());
        assertThat(after.getAbnormalRemark()).contains("超时");

        // 异常单资金保持冻结：既不结算也不解冻（契约 D9）
        BigDecimal frozen = jdbcTemplate.queryForObject(
                "SELECT frozen FROM wallet_account WHERE user_id = ?", BigDecimal.class, USER_ID);
        assertThat(frozen).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("刚开始服务的订单不成为异常候选")
    void freshlyStartedOrderIsNotAbnormalCandidate() {
        String orderNo = insertOrder(OrderState.IN_SERVICE.getCode(),
                LocalDateTime.now(), LocalDateTime.now(), null, WORKER_ID);
        assertThat(orderService.findOverdueInServiceOrderNos(500)).doesNotContain(orderNo);
    }

    // ---------------- ④ 留痕超时 ----------------

    @Test
    @DisplayName("服务中且无留痕的订单出现在留痕超时候选中")
    void orderWithoutTraceIsCandidate() {
        LocalDateTime started = LocalDateTime.now()
                .minusMinutes(jobProperties.getTraceTimeoutMinutes() + 10);
        String orderNo = insertOrder(OrderState.IN_SERVICE.getCode(),
                LocalDateTime.now().minusDays(1), started, null, WORKER_ID);

        List<ServiceOrder> candidates = orderService.findOrdersMissingTrace(500);
        assertThat(candidates).extracting(ServiceOrder::getOrderNo).contains(orderNo);
    }

    // ---------------- 任务编排 ----------------

    /** 真实执行一轮全部任务，确认不抛异常（覆盖 Spring 装配与四任务串行互不干扰） */
    @Test
    @DisplayName("runAll 可完整执行一轮而不抛异常")
    void runAllCompletesWithoutError() {
        scheduledOrderJobs.runAll();
    }

    /** 总开关关闭时 runAll 直接返回 */
    @Test
    @DisplayName("总开关关闭时 runAll 不执行任何查询")
    void disabledRunAllDoesNothing() {
        boolean original = jobProperties.isEnabled();
        try {
            jobProperties.setEnabled(false);
            scheduledOrderJobs.runAll();
        } finally {
            jobProperties.setEnabled(original);
        }
    }

    /** 自动确认完整链路：状态流转 + 结算 + SYSTEM 操作人日志 */
    @Test
    @DisplayName("自动确认：40 -> 50 并写入 SYSTEM 操作人日志")
    void autoConfirmCompletesAndLogsSystemOperator() {
        LocalDateTime finished = LocalDateTime.now()
                .minusMinutes(jobProperties.getAutoConfirmMinutes() + 10);
        String orderNo = insertOrder(OrderState.PENDING_CONFIRM.getCode(),
                LocalDateTime.now().minusDays(3), null, finished, WORKER_ID);

        assertThat(orderService.systemAutoConfirm(orderNo)).isTrue();

        ServiceOrder after = orderService.getByOrderNo(orderNo);
        assertThat(after.getOrderStatus()).isEqualTo(OrderState.COMPLETED.getCode());
        assertThat(after.getConfirmedAt()).isNotNull();

        // 流转日志的操作人必须是 SYSTEM（契约 §11 Q5）
        Integer systemLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_status_log WHERE order_no = ? AND operator_type = 'SYSTEM' "
                        + "AND event = 'AUTO_CONFIRM'", Integer.class, orderNo);
        assertThat(systemLogs).isEqualTo(1);
    }
}
