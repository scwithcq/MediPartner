package com.dsc.medipartner.common.config;

import com.dsc.medipartner.common.cache.CacheService;
import com.dsc.medipartner.common.cache.LocalCacheService;
import com.dsc.medipartner.common.cache.RedisCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缓存装配决策测试（阶段 5）。
 *
 * <p>本类锁死的是<b>整个阶段 5 最关键的一条行为</b>：Redis 不可用时应用必须照常启动。
 * 这条路径平时看不见（本机 Redis 常开时永远走 Redis 分支），一旦失效就会在
 * CI 或运维忘记起 Redis 时导致<b>应用直接起不来</b>，因此必须有单测钉住。
 */
class CacheConfigTest {

    private final CacheConfig config = new CacheConfig();

    /** 构造一个 PING 返回指定结果的连接工厂 */
    private RedisConnectionFactory factoryReturning(String pingResult, RuntimeException failure) {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(connection);
        if (failure != null) {
            when(connection.ping()).thenThrow(failure);
        } else {
            when(connection.ping()).thenReturn(pingResult);
        }
        return factory;
    }

    private RedisTemplate<String, String> templateMock() {
        RedisTemplate<String, String> template = mock(RedisTemplate.class);
        when(template.getConnectionFactory()).thenReturn(mock(RedisConnectionFactory.class));
        return template;
    }

    // ---------------- 降级：Redis 不可用时绝不能启动失败 ----------------

    @Test
    void fallsBackToLocalWhenPingThrows() {
        RedisConnectionFactory factory = factoryReturning(null,
                new org.springframework.data.redis.RedisConnectionFailureException("Unable to connect to Redis"));

        CacheService service = config.cacheService(factory, templateMock(), "auto", true);

        assertThat(service).isInstanceOf(LocalCacheService.class);
        assertThat(service.isRedisBacked()).isFalse();
    }

    @Test
    void fallsBackToLocalWhenPingReturnsNull() {
        CacheService service = config.cacheService(factoryReturning(null, null), templateMock(), "auto", true);
        assertThat(service).isInstanceOf(LocalCacheService.class);
    }

    /** PING 返回非 PONG（例如代理返回错误串）同样视为不可用 */
    @Test
    void fallsBackToLocalWhenPingIsNotPong() {
        CacheService service = config.cacheService(factoryReturning("ERR something", null),
                templateMock(), "auto", true);
        assertThat(service).isInstanceOf(LocalCacheService.class);
    }

    /**
     * 降级后返回的对象必须<b>立即可用</b>，而不是抛出「已损坏」的代理。
     * 这验证降级不是「装作能用」，而是真的能用。
     */
    @Test
    void degradedServiceIsImmediatelyUsable() {
        CacheService service = config.cacheService(factoryReturning(null,
                new RuntimeException("down")), templateMock(), "auto", true);

        service.set("k", "v", java.time.Duration.ofMinutes(1));
        assertThat(service.get("k")).isEqualTo("v");
        assertThat(service.increment("cnt", java.time.Duration.ofMinutes(1))).isEqualTo(1L);
    }

    // ---------------- 正常：Redis 可用时用 Redis ----------------

    @Test
    void usesRedisWhenPingSucceeds() {
        CacheService service = config.cacheService(factoryReturning("PONG", null),
                new StringRedisTemplate(), "auto", true);

        assertThat(service).isInstanceOf(RedisCacheService.class);
        assertThat(service.isRedisBacked()).isTrue();
    }

    /** PING 大小写不敏感（部分兼容实现回小写 pong） */
    @Test
    void acceptsLowercasePong() {
        CacheService service = config.cacheService(factoryReturning("pong", null),
                new StringRedisTemplate(), "auto", true);
        assertThat(service).isInstanceOf(RedisCacheService.class);
    }

    // ---------------- 显式配置优先于探测 ----------------

    /** medi.cache.type=local 时不探测即用本地实现（用于无 Redis 环境强制降级） */
    @Test
    void explicitLocalSkipsProbeEntirely() {
        RedisConnectionFactory factory = factoryReturning("PONG", null);

        CacheService service = config.cacheService(factory, templateMock(), "local", true);

        assertThat(service).isInstanceOf(LocalCacheService.class);
        // 显式指定 local 就不该白白做一次网络探测（拖慢启动）
        verify(factory, never()).getConnection();
    }

    /** probe-enabled=false 时直接采用 Redis 实现，不探测 */
    @Test
    void probeDisabledUsesRedisWithoutProbing() {
        RedisConnectionFactory factory = factoryReturning(null, new RuntimeException("would fail"));

        CacheService service = config.cacheService(factory, new StringRedisTemplate(), "auto", false);

        assertThat(service).isInstanceOf(RedisCacheService.class);
        verify(factory, never()).getConnection();
    }

    /** 探测后无论成败都必须关闭连接，避免连接泄漏 */
    @Test
    void probeClosesConnection() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");

        config.cacheService(factory, new StringRedisTemplate(), "auto", true);

        verify(connection).close();
    }

    /** 即使 ping 抛异常，连接仍要被关闭（finally 分支） */
    @Test
    void probeClosesConnectionEvenOnFailure() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenThrow(new RuntimeException("down"));

        config.cacheService(factory, templateMock(), "auto", true);

        verify(connection).close();
    }

    /** 关闭失败不得影响装配决策（不能因为关连接报错就启动失败） */
    @Test
    void closeFailureDoesNotBreakAssembly() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
        org.mockito.Mockito.doThrow(new RuntimeException("close failed")).when(connection).close();

        CacheService service = config.cacheService(factory, new StringRedisTemplate(), "auto", true);

        assertThat(service).isInstanceOf(RedisCacheService.class);
    }

    /** 取不到连接（而非 ping 失败）也必须降级，不能向上抛 */
    @Test
    void fallsBackWhenGetConnectionThrows() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenThrow(new RuntimeException("pool exhausted"));

        CacheService service = config.cacheService(factory, templateMock(), "auto", true);

        assertThat(service).isInstanceOf(LocalCacheService.class);
    }

    /** 防御：非 StringRedisTemplate 也应能正常工作（沿用其连接工厂） */
    @Test
    void wrapsNonStringTemplate() {
        RedisTemplate<String, String> template = mock(RedisTemplate.class);
        when(template.getConnectionFactory()).thenReturn(mock(RedisConnectionFactory.class));

        CacheService service = config.cacheService(factoryReturning("PONG", null), template, "auto", true);

        assertThat(service).isInstanceOf(RedisCacheService.class);
    }
}
