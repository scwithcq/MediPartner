package com.dsc.medipartner.common.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Redis 实现（阶段 5）。
 *
 * <p><b>容错原则</b>：Redis 是加速与共享状态，不是事实来源。任何一次调用失败都只记 WARN 并
 * 返回中性值，<b>绝不向上抛异常</b>——否则一次 Redis 抖动会让登录、下单等主流程全部 500。
 * 这与「AI 无 Key 自动降级为模板生成」是同一套哲学。
 */
@Slf4j
public class RedisCacheService implements CacheService {

    private final StringRedisTemplate redisTemplate;

    public RedisCacheService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        try {
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                redisTemplate.opsForValue().set(key, value);
            } else {
                redisTemplate.opsForValue().set(key, value, ttl);
            }
        } catch (DataAccessException e) {
            log.warn("[cache] set 失败 key={}: {}", key, e.getMessage());
        }
    }

    @Override
    public String get(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (DataAccessException e) {
            log.warn("[cache] get 失败 key={}: {}", key, e.getMessage());
            return null;
        }
    }

    @Override
    public void delete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (DataAccessException e) {
            log.warn("[cache] delete 失败 key={}: {}", key, e.getMessage());
        }
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        try {
            Boolean success = ttl == null || ttl.isZero() || ttl.isNegative()
                    ? redisTemplate.opsForValue().setIfAbsent(key, value)
                    : redisTemplate.opsForValue().setIfAbsent(key, value, ttl);
            return Boolean.TRUE.equals(success);
        } catch (DataAccessException e) {
            log.warn("[cache] setIfAbsent 失败 key={}: {}", key, e.getMessage());
            // 降级语义：拿不到锁就当作「没抢到」，让调用方跳过本次执行，
            // 而不是重复执行（定时任务重复执行的代价高于漏跑一次）
            return false;
        }
    }

    @Override
    public long increment(String key, Duration ttl) {
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L && ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                // 仅首次创建时设置过期，避免每次自增都把窗口往后推
                redisTemplate.expire(key, ttl.getSeconds(), TimeUnit.SECONDS);
            }
            return count == null ? 1L : count;
        } catch (DataAccessException e) {
            log.warn("[cache] increment 失败 key={}: {}", key, e.getMessage());
            // 降级为不限流：限流是保护性能力，不能因缓存故障阻塞全部业务
            return 1L;
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (DataAccessException e) {
            log.warn("[cache] exists 失败 key={}: {}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isRedisBacked() {
        return true;
    }
}
