package com.dsc.medipartner.common.cache;

import java.time.Duration;

/**
 * 缓存端口（阶段 5 · Redis 首次接入）。
 *
 * <p><b>为什么要有这层端口</b>：契约 §2.2~§2.6、§7.2 要求把 refreshToken、短信验证码、
 * token 黑名单、限流计数、定时任务锁放进 Redis。但本项目有一条硬约束——
 * <b>CI 的 {@code unit-test} job 与 {@code ApplicationContextSmokeTest} 必须在
 * 没有 Redis 的环境下也能通过</b>（历史教训：冒烟测试曾因隐含依赖 MySQL 导致 CI 失败）。
 * 因此业务代码<b>只依赖本接口</b>，由 {@code RedisCacheService} / {@code LocalCacheService}
 * 两个实现二选一装配，业务侧永远不知道背后是 Redis 还是进程内 Map。
 *
 * <p><b>降级语义</b>：本端口刻意只暴露最小能力集（读写、删除、计数、加锁），
 * 且所有方法<b>不允许因后端故障而抛异常</b>——缓存是加速与共享状态，不是事实来源
 * （refreshToken 之外的事实来源都在 MySQL）。后端不可用时实现应记录告警并返回中性值。
 */
public interface CacheService {

    /**
     * 写入并设置过期时间。
     *
     * @param key      缓存键
     * @param value    值（实现负责序列化）
     * @param ttl      存活时间；{@code null} 或非正数表示不过期
     */
    void set(String key, String value, Duration ttl);

    /**
     * 读取。
     *
     * @return 值；键不存在或后端不可用时返回 {@code null}
     */
    String get(String key);

    /**
     * 删除单个键。删除不存在的键是幂等操作，不报错。
     */
    void delete(String key);

    /**
     * 仅当键不存在时写入（SETNX 语义），用于限流与分布式锁。
     *
     * @return {@code true} 表示本次写入成功（键此前不存在）
     */
    boolean setIfAbsent(String key, String value, Duration ttl);

    /**
     * 原子自增，并在键首次创建时设置过期时间。
     *
     * <p>用于滑动窗口限流计数。后端不可用时返回 {@code 1}（视为首次请求，
     * 即<b>降级为不限流</b>）——限流是保护性能力，宁可放过也不能因缓存故障
     * 而让全部业务接口报错。
     *
     * @return 自增后的计数值
     */
    long increment(String key, Duration ttl);

    /**
     * 判断键是否存在。
     */
    boolean exists(String key);

    /**
     * 后端是否为真实 Redis。
     *
     * <p>供健康检查与日志提示使用。业务逻辑<b>不应</b>据此改变行为，
     * 否则降级路径会变成从未被真正执行过的死代码。
     */
    boolean isRedisBacked();
}
