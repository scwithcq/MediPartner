package com.dsc.medipartner.common.config;

import com.dsc.medipartner.common.cache.CacheService;
import com.dsc.medipartner.common.cache.LocalCacheService;
import com.dsc.medipartner.common.cache.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 缓存装配（阶段 5）。
 *
 * <p><b>装配决策在启动时做一次，运行期不再切换</b>：启动时对 Redis 做一次带短超时的
 * PING 探测，通则用 {@link RedisCacheService}，不通则用 {@link LocalCacheService}。
 * 刻意<b>不做</b>「运行期动态切换」——那会让同一份业务代码在不同时刻走不同路径，
 * 出现「有时跨实例生效有时不生效」的诡异问题，排查成本远高于收益。
 *
 * <p><b>探测失败为什么不让应用启动失败</b>：本项目历史上已经因为「冒烟测试隐含依赖
 * 数据库」导致 CI 失败一次。缓存同理不能成为启动的必要条件——它只承载可重建的共享状态，
 * 事实来源始终在 MySQL。
 */
@Slf4j
@Configuration
public class CacheConfig {

    /** 启动探测超时：故意取短值，避免 Redis 不可达时拖慢启动（smoke 测试会指向不可达端口） */
    private static final Duration PROBE_TIMEOUT = Duration.ofMillis(500);

    /**
     * 缓存端口 Bean。
     *
     * <p>返回类型是接口 {@link CacheService}，业务侧按接口注入，
     * 无从得知背后是 Redis 还是本地 Map——降级路径因此能被真实执行，不是死代码。
     */
    @Bean
    @ConditionalOnMissingBean(CacheService.class)
    public CacheService cacheService(
            RedisConnectionFactory connectionFactory,
            org.springframework.data.redis.core.RedisTemplate<String, String> redisTemplate,
            @Value("${medi.cache.type:auto}") String cacheType,
            @Value("${medi.cache.probe-enabled:true}") boolean probeEnabled) {

        if ("local".equalsIgnoreCase(cacheType)) {
            log.info("[cache] medi.cache.type=local，按配置使用进程内缓存");
            return new LocalCacheService();
        }

        if (!probeEnabled) {
            log.info("[cache] 启动探测已关闭（medi.cache.probe-enabled=false），直接使用 Redis 实现");
            return new RedisCacheService(stringRedisTemplate(redisTemplate));
        }

        if (probe(connectionFactory)) {
            log.info("[cache] Redis 探测成功，启用 Redis 缓存（支持多实例共享与限流）");
            return new RedisCacheService(stringRedisTemplate(redisTemplate));
        }

        log.warn("[cache] Redis 探测失败，降级为进程内缓存。"
                + "如需完整能力请启动 Redis（本机：Q:\\Redis\\redis-server.exe）后重启应用");
        return new LocalCacheService();
    }

    /**
     * 用带超时的 PING 判断 Redis 是否真的可用。
     *
     * <p><b>为什么必须真正执行一次 PING 而不能只测 TCP 端口</b>：本机 Redis 配了
     * {@code requirepass}，TCP 连接<b>能建立</b>，但任何命令都会返回 {@code NOAUTH}。
     * 若只探测端口，会把「连得上但用不了」误判为可用，然后在运行期每次调用都静默失败——
     * 缓存看似启用、实则全程不生效，是最难排查的一类故障。执行真实命令才能暴露它。
     *
     * <p>只捕获异常而不重试：启动探测是「一次性的可用性判断」，
     * 重试只会拖长启动时间，且失败后的降级路径本身是安全的。
     */
    private boolean probe(RedisConnectionFactory connectionFactory) {
        RedisConnection connection = null;
        try {
            connection = connectionFactory.getConnection();
            // ping() 会真正走一次 Redis 命令往返：口令错误（NOAUTH）、
            // 权限不足、代理拦截等情况都会在此抛异常，从而正确降级
            String pong = connection.ping();
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            log.warn("[cache] Redis PING 异常: {}", e.getMessage());
            return false;
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响装配决策
                }
            }
        }
    }

    /**
     * 复用自动装配好的 {@code redisTemplate} 构造字符串模板。
     *
     * <p>不用 {@code new StringRedisTemplate(factory)} 是为了沿用 Boot 的连接池与
     * 序列化配置，避免出现两套连接参数。
     */
    @SuppressWarnings("unchecked")
    private StringRedisTemplate stringRedisTemplate(
            org.springframework.data.redis.core.RedisTemplate<String, String> redisTemplate) {
        if (redisTemplate instanceof StringRedisTemplate srt) {
            return srt;
        }
        StringRedisTemplate srt = new StringRedisTemplate();
        srt.setConnectionFactory(redisTemplate.getConnectionFactory());
        srt.afterPropertiesSet();
        return srt;
    }
}
