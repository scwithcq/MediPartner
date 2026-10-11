package com.dsc.medipartner.common.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进程内降级实现测试（阶段 5）。
 *
 * <p><b>为什么必须测它</b>：CI 的 {@code unit-test} job 与冒烟测试都没有 Redis，
 * 此时全部缓存能力——refreshToken、短信验证码、限流计数、定时任务锁——
 * 实际都跑在本实现上。若只测 Redis 实现，等于线上的降级路径从未被验证过。
 *
 * <p>这些用例同时充当<b>语义基准</b>：{@code RedisCacheService} 必须与之行为一致，
 * 否则同一份业务代码在有无 Redis 的两种环境下表现不同。
 */
class LocalCacheServiceTest {

    private final LocalCacheService cache = new LocalCacheService();

    @Test
    void setThenGetReturnsValue() {
        cache.set("k", "v", Duration.ofMinutes(5));
        assertThat(cache.get("k")).isEqualTo("v");
    }

    @Test
    void getMissingKeyReturnsNull() {
        assertThat(cache.get("nope")).isNull();
    }

    @Test
    void deleteRemovesKey() {
        cache.set("k", "v", Duration.ofMinutes(5));
        cache.delete("k");
        assertThat(cache.get("k")).isNull();
    }

    /** 删除不存在的键是幂等的，不得抛异常（登出场景会对已过期的 token 重复删除） */
    @Test
    void deleteMissingKeyIsIdempotent() {
        cache.delete("never-existed");
        cache.delete("never-existed");
        assertThat(cache.get("never-existed")).isNull();
    }

    @Test
    void expiredEntryIsTreatedAsAbsent() throws InterruptedException {
        cache.set("k", "v", Duration.ofMillis(40));
        assertThat(cache.get("k")).isEqualTo("v");
        Thread.sleep(80);
        assertThat(cache.get("k")).isNull();
        assertThat(cache.exists("k")).isFalse();
    }

    /** ttl 为 null / 0 / 负数表示不过期 */
    @Test
    void nullOrNonPositiveTtlMeansNoExpiry() throws InterruptedException {
        cache.set("a", "1", null);
        cache.set("b", "2", Duration.ZERO);
        cache.set("c", "3", Duration.ofSeconds(-5));
        Thread.sleep(30);
        assertThat(cache.get("a")).isEqualTo("1");
        assertThat(cache.get("b")).isEqualTo("2");
        assertThat(cache.get("c")).isEqualTo("3");
    }

    // ---------------- setIfAbsent：分布式锁与限流的基础 ----------------

    @Test
    void setIfAbsentSucceedsOnlyOnce() {
        assertThat(cache.setIfAbsent("lock", "owner1", Duration.ofMinutes(1))).isTrue();
        assertThat(cache.setIfAbsent("lock", "owner2", Duration.ofMinutes(1))).isFalse();
        assertThat(cache.get("lock")).isEqualTo("owner1");
    }

    /** 锁过期后应可被重新获取，否则定时任务会永久卡死 */
    @Test
    void setIfAbsentSucceedsAgainAfterExpiry() throws InterruptedException {
        assertThat(cache.setIfAbsent("lock", "owner1", Duration.ofMillis(40))).isTrue();
        Thread.sleep(80);
        assertThat(cache.setIfAbsent("lock", "owner2", Duration.ofMinutes(1))).isTrue();
        assertThat(cache.get("lock")).isEqualTo("owner2");
    }

    // ---------------- increment：限流计数 ----------------

    @Test
    void incrementStartsAtOneAndAccumulates() {
        assertThat(cache.increment("cnt", Duration.ofMinutes(1))).isEqualTo(1L);
        assertThat(cache.increment("cnt", Duration.ofMinutes(1))).isEqualTo(2L);
        assertThat(cache.increment("cnt", Duration.ofMinutes(1))).isEqualTo(3L);
    }

    @Test
    void incrementRestartsAfterWindowExpires() throws InterruptedException {
        assertThat(cache.increment("cnt", Duration.ofMillis(40))).isEqualTo(1L);
        assertThat(cache.increment("cnt", Duration.ofMillis(40))).isEqualTo(2L);
        Thread.sleep(80);
        assertThat(cache.increment("cnt", Duration.ofMillis(40))).isEqualTo(1L);
    }

    /**
     * 自增不得延长窗口。
     *
     * <p>这是与 Redis 实现对齐的关键语义：{@code INCR} 只在键首次创建时设过期。
     * 若每次自增都刷新 TTL，高频请求下的计数窗口会无限后移，限流形同虚设。
     */
    @Test
    void incrementDoesNotExtendWindow() throws InterruptedException {
        cache.increment("cnt", Duration.ofMillis(120));
        Thread.sleep(60);
        cache.increment("cnt", Duration.ofMillis(120));
        Thread.sleep(80);
        // 距首次创建已 140ms > 120ms，窗口应已结束
        assertThat(cache.increment("cnt", Duration.ofMillis(120))).isEqualTo(1L);
    }

    @Test
    void existsReflectsPresence() {
        assertThat(cache.exists("k")).isFalse();
        cache.set("k", "v", Duration.ofMinutes(1));
        assertThat(cache.exists("k")).isTrue();
    }

    /** 降级实现必须如实上报自己不是 Redis，便于启动日志与健康检查识别 */
    @Test
    void reportsItselfAsNotRedisBacked() {
        assertThat(cache.isRedisBacked()).isFalse();
    }
}
