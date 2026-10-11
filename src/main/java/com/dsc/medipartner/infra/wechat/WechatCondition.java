package com.dsc.medipartner.infra.wechat;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 微信实现的装配条件（阶段 6）。
 *
 * <p><b>为什么不用 {@code @ConditionalOnProperty}</b>：开关有 {@code auto} 这一档，
 * 需要「按凭证是否齐备」动态决定，而注解只能比对配置字面值。
 * 在本类里集中判定，可保证 {@link #mock()} 与 {@link #real()} 互为反命题——
 * 两者绝不可能同时成立（导致 Bean 冲突）或同时不成立（导致启动缺 Bean）。
 */
public final class WechatCondition {

    private WechatCondition() {
    }

    /** 走 mock 实现：显式 true，或 auto 模式下凭证缺失 */
    public static class Mock implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return resolve(context).useMock();
        }
    }

    /** 走真实实现：显式 false，或 auto 模式下凭证齐备 */
    public static class Real implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !resolve(context).useMock();
        }
    }

    /** 从 Environment 读取微信配置，构造一个仅用于判定的实例 */
    private static WechatProperties resolve(ConditionContext context) {
        var env = context.getEnvironment();
        WechatProperties props = new WechatProperties();
        props.setMock(env.getProperty("medi.wechat.mock", "auto"));
        props.setAppid(env.getProperty("medi.wechat.appid", ""));
        props.setAppsecret(env.getProperty("medi.wechat.appsecret", ""));
        return props;
    }
}
