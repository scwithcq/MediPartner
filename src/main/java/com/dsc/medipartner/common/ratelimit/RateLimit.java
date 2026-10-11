package com.dsc.medipartner.common.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流（阶段 5，契约 §2.5 / §2.6 / §3.8）。
 *
 * <p><b>为什么做成注解而非在 Service 里手写计数</b>：限流是横切关注点，
 * 手写在每个方法里会出现「这个接口限了、那个忘了限」的漏洞，
 * 且阈值散落各处难以统一调整。注解把「限多少」与「怎么限」分离：
 * 业务方法只声明意图，实现集中在 {@link RateLimitAspect}。
 *
 * <p><b>超限返回 {@code 1005 REPEAT_REQUEST}</b>（契约附录 B.2 已定义该码），
 * 与 §2.5 短信「60s 一次」同一语义，前端无需区分两种限流。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 限流名称：作为 Redis 键的一部分，<b>同一名称共享配额</b>。
     *
     * <p>不填则用「类名.方法名」，适合每个接口独立配额的常见场景。
     */
    String name() default "";

    /** 窗口内允许的最大次数 */
    int limit();

    /** 窗口长度（秒） */
    int windowSeconds();

    /**
     * 限流维度。
     *
     * <p>默认按用户；未登录场景（如 §2.5 发验证码）按手机号或 IP，
     * 否则所有匿名用户会共享同一个计数器——第一个用户刷满就把所有人挡住。
     */
    Dimension dimension() default Dimension.USER;

    /** 限流维度 */
    enum Dimension {
        /** 按登录用户（userId）；未登录时退化为 IP */
        USER,
        /** 按请求 IP */
        IP,
        /**
         * 按请求体中的手机号字段。
         *
         * <p>用于 §2.5：限流对象是「手机号」而非「调用者」——
         * 否则同一攻击者换 IP 就能对同一号码反复发短信（短信轰炸 + 资损）。
         */
        PHONE
    }
}
