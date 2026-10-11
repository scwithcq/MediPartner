package com.dsc.medipartner.module.job;

import com.dsc.medipartner.common.cache.CacheKeys;
import com.dsc.medipartner.common.cache.CacheService;
import com.dsc.medipartner.module.job.config.JobProperties;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.entity.OrderTrace;
import com.dsc.medipartner.module.order.service.OrderService;
import com.dsc.medipartner.module.order.service.TraceService;
import com.dsc.medipartner.module.user.domain.enums.CreditChangeType;
import com.dsc.medipartner.module.user.service.CreditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 阶段 5 定时任务（契约 §7.2）。
 *
 * <p>四类任务，全部<b>复用状态机已有事件</b>，不新增流转逻辑：
 * <ol>
 *   <li>24h 无人接单 → 自动取消（{@code TIMEOUT_CANCEL}）</li>
 *   <li>48h 未确认 → 自动完成并结算（{@code AUTO_CONFIRM}）</li>
 *   <li>12h 未结束 → 标记异常（{@code MARK_ABNORMAL}）</li>
 *   <li>45min 无留痕 → 告警并扣信用分</li>
 * </ol>
 *
 * <p><b>为什么每类任务都加 Redis 锁</b>：定时任务在单机跑没问题，但一旦部署第二个实例
 * （或本地起了两个进程联调），同一时刻两个实例会同时扫到同一批订单。
 * 虽然 {@code OrderService} 的系统方法都带乐观锁、不会重复流转，
 * 但「信用分扣分」这类操作不是状态流转，重复执行会真的多扣分。
 * 加锁把「同一任务同时只有一个实例在跑」变成显式约束，而不是依赖下游幂等。
 *
 * <p>锁的降级语义：拿不到锁就跳过本轮（{@code CacheService.setIfAbsent} 在后端不可用时
 * 返回 false）。<b>本地降级实现下锁仅本进程有效</b>，多实例场景必须切真实 Redis——
 * 这也是启动时若探测不到 Redis 会打 WARN 的原因之一。
 */
@Slf4j
@Component
public class ScheduledOrderJobs {

    /** 任务名：同时用作 Redis 锁键与日志标识 */
    private static final String JOB_TIMEOUT_CANCEL = "timeoutCancel";
    private static final String JOB_AUTO_CONFIRM = "autoConfirm";
    private static final String JOB_ABNORMAL = "abnormal";
    private static final String JOB_TRACE_TIMEOUT = "traceTimeout";

    /**
     * 锁的持有时间：略大于「一轮扫描的预期耗时」即可。
     *
     * <p>取 5 分钟：正常一轮几百条订单只需秒级。若某轮真跑了 5 分钟以上，
     * 说明数据量异常，此时让锁过期、下一轮重新尝试，比永久卡死更安全
     * （锁永久卡死的后果是该任务再也不执行，且没有任何报错）。
     */
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);

    private final OrderService orderService;
    private final TraceService traceService;
    private final CreditService creditService;
    private final CacheService cacheService;
    private final JobProperties jobProperties;

    public ScheduledOrderJobs(OrderService orderService, TraceService traceService,
                              CreditService creditService, CacheService cacheService,
                              JobProperties jobProperties) {
        this.orderService = orderService;
        this.traceService = traceService;
        this.creditService = creditService;
        this.cacheService = cacheService;
        this.jobProperties = jobProperties;
    }

    /**
     * 主扫描入口：按配置间隔依次执行四类任务。
     *
     * <p>用一个入口而不是四个独立 {@code @Scheduled}：四个任务串行执行时，
     * 数据库压力更平缓，且日志按轮次聚合，排查「这一轮发生了什么」更直观。
     * 单个任务抛异常不影响后续任务（各自 try-catch），
     * 否则「超时取消」出错会连带「自动确认」也不跑。
     */
    @Scheduled(fixedDelayString = "${medi.job.scan-interval-seconds:60}000")
    public void runAll() {
        if (!jobProperties.isEnabled()) {
            return;
        }
        runGuarded(JOB_TIMEOUT_CANCEL, this::timeoutCancel);
        runGuarded(JOB_AUTO_CONFIRM, this::autoConfirm);
        runGuarded(JOB_ABNORMAL, this::markAbnormal);
        runGuarded(JOB_TRACE_TIMEOUT, this::traceTimeout);
    }

    /**
     * 统一包装：抢锁 + 异常隔离 + 耗时日志。
     *
     * @param name 任务名（锁键与日志用）
     * @param task 任务体
     */
    private void runGuarded(String name, Runnable task) {
        // 抢不到锁说明另一实例正在跑同一任务，静默跳过（不算错误）
        if (!cacheService.setIfAbsent(CacheKeys.job(name), "running", LOCK_TTL)) {
            log.debug("[job] {} 未获取到锁，跳过本轮", name);
            return;
        }
        long start = System.currentTimeMillis();
        try {
            task.run();
        } catch (Exception e) {
            // 单个任务失败不得影响其它任务；定时任务无人值守，必须留足日志
            log.error("[job] {} 执行失败: {}", name, e.getMessage(), e);
        } finally {
            cacheService.delete(CacheKeys.job(name));
            long cost = System.currentTimeMillis() - start;
            if (cost > 5000) {
                // 慢轮次值得关注：可能是数据量增长或索引缺失
                log.warn("[job] {} 耗时较长 {}ms", name, cost);
            }
        }
    }

    /** ① 超时无人接单 → 自动取消并解冻资金 */
    private void timeoutCancel() {
        List<String> orderNos = orderService.findTimeoutWaitingOrderNos(jobProperties.getBatchSize());
        int affected = 0;
        for (String orderNo : orderNos) {
            try {
                if (orderService.systemTimeoutCancel(orderNo)) {
                    affected++;
                }
            } catch (Exception e) {
                // 单笔失败继续处理其余，避免一条坏数据卡住整批
                log.warn("[job] 超时取消失败 orderNo={}: {}", orderNo, e.getMessage());
            }
        }
        if (affected > 0) {
            log.info("[job] 超时未接单自动取消 {} 笔（候选 {}）", affected, orderNos.size());
        }
    }

    /**
     * ② 超时未确认 → 自动完成并结算。
     *
     * <p>候选查询已排除被申诉暂停自动确认的订单（阶段 4 预留的 {@code auto_confirm_paused}）。
     *
     * <p><b>失败退避</b>：结算失败（如钱包数据不一致）时若不退避，该订单会在每个扫描周期
     * 被反复重试——实测每 20 秒报一次错、永不停歇，既刷爆日志又持续占用数据库。
     * 这里对失败订单做标记，跳过接下来的一段时间再重试，给人工介入留出窗口。
     */
    private void autoConfirm() {
        List<String> orderNos = orderService.findAutoConfirmableOrderNos(jobProperties.getBatchSize());
        int affected = 0;
        int skipped = 0;
        for (String orderNo : orderNos) {
            if (isBackingOff(orderNo)) {
                skipped++;
                continue;
            }
            try {
                if (orderService.systemAutoConfirm(orderNo)) {
                    affected++;
                    clearBackoff(orderNo);
                }
            } catch (Exception e) {
                markBackoff(orderNo);
                log.warn("[job] 自动确认失败，{} 分钟内不再重试该单 orderNo={}: {}",
                        jobProperties.getFailureBackoffMinutes(), orderNo, e.getMessage());
            }
        }
        if (affected > 0 || skipped > 0) {
            log.info("[job] 超时未确认自动完成 {} 笔（候选 {}，退避跳过 {}）",
                    affected, orderNos.size(), skipped);
        }
    }

    /**
     * 该订单是否处于失败退避期。
     *
     * <p>缓存不可用时 {@code exists} 返回 false，即退避失效、恢复原行为——
     * 宁可多刷日志也不能让订单永远不再被处理。
     */
    private boolean isBackingOff(String orderNo) {
        return cacheService.exists(CacheKeys.job(JOB_AUTO_CONFIRM + ":backoff:" + orderNo));
    }

    /**
     * 标记失败退避，避免同一订单在每个扫描周期被反复重试。
     *
     * <p>⚠ 退避时长 ≤ 0 时<b>不写标记</b>：缓存层把 TTL 0 视为「永不过期」，
     * 若照写会把该订单永久锁在退避态、再也不处理（与 SMS 间隔限流同一类陷阱）。
     * 语义上「退避 0 分钟」应当是「不退避」。
     */
    private void markBackoff(String orderNo) {
        if (jobProperties.getFailureBackoffMinutes() <= 0) {
            return;
        }
        cacheService.set(CacheKeys.job(JOB_AUTO_CONFIRM + ":backoff:" + orderNo), "1",
                Duration.ofMinutes(jobProperties.getFailureBackoffMinutes()));
    }

    private void clearBackoff(String orderNo) {
        cacheService.delete(CacheKeys.job(JOB_AUTO_CONFIRM + ":backoff:" + orderNo));
    }

    /** ③ 服务中长时间未结束 → 标记异常（资金保持冻结，交管理端收尾） */
    private void markAbnormal() {
        List<String> orderNos = orderService.findOverdueInServiceOrderNos(jobProperties.getBatchSize());
        int affected = 0;
        for (String orderNo : orderNos) {
            try {
                if (orderService.systemMarkAbnormal(orderNo, "服务超时未结束，系统标记异常")) {
                    affected++;
                }
            } catch (Exception e) {
                log.warn("[job] 标记异常失败 orderNo={}: {}", orderNo, e.getMessage());
            }
        }
        if (affected > 0) {
            log.info("[job] 服务超时未结束，标记异常 {} 笔（候选 {}）", affected, orderNos.size());
        }
    }

    /**
     * ④ 服务开始后长时间无任何留痕 → 告警并（可选）扣信用分。
     *
     * <p><b>扣分必须幂等</b>：本任务默认每 60 秒跑一轮，若不限制，
     * 同一订单会被反复扣 2 分，一小时就能把信用分清零。
     * 这里靠两重保护：
     * <ol>
     *   <li>{@code tracePenaltyOncePerOrder} 时用 Redis 标记「本单已扣过分」</li>
     *   <li>即便标记丢失（Redis 降级/重启），{@code credit_change_log} 的
     *       {@code (biz_no, type)} 唯一约束也会挡住重复记账</li>
     * </ol>
     */
    private void traceTimeout() {
        List<ServiceOrder> orders = orderService.findOrdersMissingTrace(jobProperties.getBatchSize());
        int alerted = 0;
        for (ServiceOrder order : orders) {
            String orderNo = order.getOrderNo();
            try {
                if (hasAnyTrace(orderNo)) {
                    continue;
                }
                alerted++;
                // 告警一定打：留痕缺失意味着纠纷无法仲裁，必须留下可检索的记录
                log.warn("[job] 留痕超时：服务已开始但无任何留痕 orderNo={} workerId={} startedAt={}",
                        orderNo, order.getWorkerId(), order.getStartedAt());

                if (jobProperties.isTraceTimeoutPenalty() && order.getWorkerId() != null
                        && markPenaltyIfFirst(orderNo)) {
                    creditService.change(order.getWorkerId(), CreditChangeType.TRACE_TIMEOUT, orderNo, null);
                    log.info("[job] 留痕超时扣信用分 orderNo={} workerId={}", orderNo, order.getWorkerId());
                }
            } catch (Exception e) {
                log.warn("[job] 留痕超时处理失败 orderNo={}: {}", orderNo, e.getMessage());
            }
        }
        if (alerted > 0) {
            log.info("[job] 留痕超时告警 {} 笔（候选 {}）", alerted, orders.size());
        }
    }

    /**
     * 标记「本单已因留痕超时扣过分」，返回 true 表示本次是第一次。
     *
     * <p>TTL 取 7 天：远长于任何订单的服务时长，足以覆盖重复扫描；
     * 又不会永久占用内存（订单结案后再无意义）。
     */
    private boolean markPenaltyIfFirst(String orderNo) {
        if (!jobProperties.isTracePenaltyOncePerOrder()) {
            return true;
        }
        return cacheService.setIfAbsent(
                CacheKeys.job(JOB_TRACE_TIMEOUT + ":penalty:" + orderNo), "1", Duration.ofDays(7));
    }

    /** 该订单是否已有留痕（TRACE 或 VOUCHER 均算）；查询失败按「有留痕」处理，避免误扣分 */
    private boolean hasAnyTrace(String orderNo) {
        try {
            List<OrderTrace> traces = traceService.listByOrder(orderNo);
            return traces != null && !traces.isEmpty();
        } catch (Exception e) {
            log.warn("[job] 查询留痕失败，按有留痕处理以避免误扣分 orderNo={}: {}", orderNo, e.getMessage());
            return true;
        }
    }
}
