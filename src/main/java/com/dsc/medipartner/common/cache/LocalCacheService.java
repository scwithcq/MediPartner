package com.dsc.medipartner.common.cache;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内降级实现（阶段 5）。
 *
 * <p><b>什么时候用它</b>：启动时探测不到 Redis（本机 Docker 常态未运行，CI 无 Redis），
 * 或显式配置 {@code medi.cache.type=local}。有了它，冒烟测试与单元测试无需任何外部依赖
 * 即可覆盖认证、限流、定时任务锁的全部业务分支。
 *
 * <p><b>与 Redis 的语义差异（必须知情）</b>：
 * <ol>
 *   <li><b>不跨实例</b>：多实例部署时各进程各持一份，限流与定时任务锁退化为单机语义。
 *       本项目为课设单体部署，可接受；生产多实例必须切 Redis。</li>
 *   <li><b>重启即失</b>：refreshToken 与短信验证码会随进程重启失效，用户需重新登录。</li>
 * </ol>
 * 这也是它在日志里明确告警的原因——避免线上误配后无人察觉。
 *
 * <p>过期采用<b>惰性删除</b>（读时判断 + 写入时顺带清理），不启后台线程：
 * 定时任务线程池属阶段 5 新增资源，不应为一个降级实现再引入额外的常驻调度。
 */
@Slf4j
public class LocalCacheService implements CacheService {

    /** 惰性清理的触发间隔（按写入次数计），避免每次写入都全表扫描 */
    private static final int SWEEP_EVERY_WRITES = 256;

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final AtomicLong writeCounter = new AtomicLong();

    /** 缓存条目：值 + 过期时刻（null 表示永不过期） */
    private record Entry(String value, Instant expireAt) {

        boolean expired() {
            return expireAt != null && !Instant.now().isBefore(expireAt);
        }
    }

    public LocalCacheService() {
        log.warn("[cache] 未探测到 Redis，已降级为进程内缓存："
                + "限流与定时任务锁仅在本实例生效，重启后 refreshToken 与短信验证码失效");
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        store.put(key, new Entry(value, expireAt(ttl)));
        sweepIfNeeded();
    }

    @Override
    public String get(String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expired()) {
            store.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    @Override
    public void delete(String key) {
        store.remove(key);
    }

    @Override
    public synchronized boolean setIfAbsent(String key, String value, Duration ttl) {
        Entry entry = store.get(key);
        if (entry != null && !entry.expired()) {
            return false;
        }
        store.put(key, new Entry(value, expireAt(ttl)));
        sweepIfNeeded();
        return true;
    }

    @Override
    public synchronized long increment(String key, Duration ttl) {
        Entry entry = store.get(key);
        if (entry == null || entry.expired()) {
            store.put(key, new Entry("1", expireAt(ttl)));
            sweepIfNeeded();
            return 1L;
        }
        long next = Long.parseLong(entry.value()) + 1;
        // 保留原过期时刻：与 Redis 实现一致，自增不延长窗口
        store.put(key, new Entry(String.valueOf(next), entry.expireAt()));
        return next;
    }

    @Override
    public boolean exists(String key) {
        return get(key) != null;
    }

    @Override
    public boolean isRedisBacked() {
        return false;
    }

    /**
     * 返回当前所有<b>未过期</b>键的快照（仅用于测试与诊断）。
     *
     * <p>存在的理由：有一类缺陷只能靠「数键的个数」发现——例如重复签发 refreshToken 时
     * 忘记清理上一枚反向键，会随登录次数不断积压（真实 Redis 上验证时才暴露）。
     * 只断言「旧 token 失效了」的测试对这种泄漏完全无感，因此需要能观测键集合。
     */
    public java.util.Set<String> snapshotKeys() {
        return store.entrySet().stream()
                .filter(e -> !e.getValue().expired())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
    }

    private Instant expireAt(Duration ttl) {
        return ttl == null || ttl.isZero() || ttl.isNegative() ? null : Instant.now().plus(ttl);
    }

    /** 顺带清理过期条目，防止长期运行后 Map 无限增长 */
    private void sweepIfNeeded() {
        if (writeCounter.incrementAndGet() % SWEEP_EVERY_WRITES != 0) {
            return;
        }
        store.entrySet().removeIf(e -> e.getValue().expired());
    }
}
