package com.dsc.medipartner.common.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Redis 实现测试（阶段 5）。
 *
 * <p><b>为什么用 {@code assumeTrue} 做条件跳过</b>：本项目有一条硬约束——
 * CI 的 {@code unit-test} job <b>没有 Redis</b>，而本机 Docker 常态未运行。
 * 若把 Redis 作为测试的硬依赖，就是重演「冒烟测试隐含依赖 MySQL 导致 CI 失败」那个坑。
 * 因此：Redis 在 → 真实验证；Redis 不在 → 跳过并<b>由 {@link LocalCacheServiceTest} 承担语义基准</b>。
 *
 * <p><b>为什么探测要自己写而不是注入 {@code CacheService}</b>：本测试要验证的正是
 * 「Redis 真的连得上时，{@link RedisCacheService} 的行为与本地实现一致」，
 * 若注入的是被降级过的 Bean，测试会静默地什么都没验证。
 */
class RedisCacheServiceTest {

    /**
     * 独立探测：只有确认 Redis 可用才继续，避免 CI 报错与误报成功。
     *
     * <p><b>探测必须走真实命令而非 TCP 端口</b>：本机 Redis 配了 {@code requirepass}，
     * 端口能连上但命令返回 {@code NOAUTH}。只测端口会把「连得上但用不了」误判为可用。
     * 这里直接构造带口令的连接并 PING，与 {@code CacheConfig} 的判定口径保持一致。
     */
    private RedisCacheService newServiceOrSkip() {
        String host = System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        String password = System.getenv().getOrDefault("REDIS_PASSWORD", "cq");

        org.springframework.data.redis.connection.RedisStandaloneConfiguration standalone =
                new org.springframework.data.redis.connection.RedisStandaloneConfiguration(host, port);
        if (!password.isEmpty()) {
            standalone.setPassword(password);
        }
        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory factory =
                new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(standalone);
        factory.afterPropertiesSet();

        try (org.springframework.data.redis.connection.RedisConnection conn = factory.getConnection()) {
            if (!"PONG".equalsIgnoreCase(conn.ping())) {
                assumeTrue(false, "Redis PING 未返回 PONG，跳过 Redis 实现测试");
                return null;
            }
        } catch (Exception e) {
            assumeTrue(false, "Redis 不可用（" + host + ":" + port + "）："
                    + e.getMessage() + "，跳过 Redis 实现测试");
            return null;
        }

        org.springframework.data.redis.core.StringRedisTemplate template =
                new org.springframework.data.redis.core.StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return new RedisCacheService(template);
    }

    /** 每次用唯一键，避免与真实业务数据（或并发测试）互相干扰 */
    private String key(String suffix) {
        return "test:stage5:" + UUID.randomUUID() + ":" + suffix;
    }

    @Test
    void setThenGetReturnsValue() {
        RedisCacheService cache = newServiceOrSkip();
        String k = key("basic");
        try {
            cache.set(k, "v", Duration.ofMinutes(1));
            assertThat(cache.get(k)).isEqualTo("v");
        } finally {
            cache.delete(k);
        }
    }

    @Test
    void reportsItselfAsRedisBacked() {
        assertThat(newServiceOrSkip().isRedisBacked()).isTrue();
    }

    @Test
    void expiredEntryIsTreatedAsAbsent() throws InterruptedException {
        RedisCacheService cache = newServiceOrSkip();
        String k = key("ttl");
        try {
            cache.set(k, "v", Duration.ofMillis(80));
            Thread.sleep(250);
            assertThat(cache.get(k)).isNull();
        } finally {
            cache.delete(k);
        }
    }

    @Test
    void setIfAbsentSucceedsOnlyOnce() {
        RedisCacheService cache = newServiceOrSkip();
        String k = key("lock");
        try {
            assertThat(cache.setIfAbsent(k, "owner1", Duration.ofMinutes(1))).isTrue();
            assertThat(cache.setIfAbsent(k, "owner2", Duration.ofMinutes(1))).isFalse();
            assertThat(cache.get(k)).isEqualTo("owner1");
        } finally {
            cache.delete(k);
        }
    }

    @Test
    void incrementStartsAtOneAndAccumulates() {
        RedisCacheService cache = newServiceOrSkip();
        String k = key("cnt");
        try {
            assertThat(cache.increment(k, Duration.ofMinutes(1))).isEqualTo(1L);
            assertThat(cache.increment(k, Duration.ofMinutes(1))).isEqualTo(2L);
            assertThat(cache.increment(k, Duration.ofMinutes(1))).isEqualTo(3L);
        } finally {
            cache.delete(k);
        }
    }

    /** 自增只在首次创建时设过期：与本地实现语义一致，否则限流窗口会被无限后推 */
    @Test
    void incrementDoesNotExtendWindow() throws InterruptedException {
        RedisCacheService cache = newServiceOrSkip();
        String k = key("window");
        try {
            cache.increment(k, Duration.ofMillis(400));
            Thread.sleep(200);
            cache.increment(k, Duration.ofMillis(400));
            Thread.sleep(300);
            // 距首次创建已 500ms > 400ms，窗口应已结束
            assertThat(cache.increment(k, Duration.ofMillis(400))).isEqualTo(1L);
        } finally {
            cache.delete(k);
        }
    }

    @Test
    void deleteRemovesKeyAndIsIdempotent() {
        RedisCacheService cache = newServiceOrSkip();
        String k = key("del");
        cache.set(k, "v", Duration.ofMinutes(1));
        cache.delete(k);
        assertThat(cache.get(k)).isNull();
        cache.delete(k);
        assertThat(cache.exists(k)).isFalse();
    }
}
