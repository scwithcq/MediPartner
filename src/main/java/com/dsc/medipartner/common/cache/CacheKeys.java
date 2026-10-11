package com.dsc.medipartner.common.cache;

/**
 * 缓存键命名约定（阶段 5）。
 *
 * <p><b>集中定义的理由</b>：键名散落在各处时，最容易出的错是「写入用
 * {@code medi:sms:}、读取用 {@code medi:verify:}」——两边都编译通过、单测可能也过，
 * 但运行期永远取不到值，表现为「验证码永远错误」。集中在一处可让这类问题在编译期消失。
 *
 * <p>统一前缀 {@code medi:} 便于运维在共享 Redis 上识别本项目键，避免与其它服务冲突。
 */
public final class CacheKeys {

    /** refreshToken 前缀：{@code medi:refresh:<userId>}（契约 §2.2） */
    public static final String REFRESH = "medi:refresh:";

    /**
     * refreshToken 反向查找前缀：{@code medi:refresh:token:<指纹>} → userId。
     *
     * <p>存在理由：刷新接口是匿名的（凭 refreshToken 鉴权），而 refreshToken 是随机串、
     * 不含身份信息，必须靠反向键才能从 token 反查 userId。
     * ⚠ 本前缀以 {@link #REFRESH} 开头，因此 {@code CacheKeys.refresh(userId)} 的
     * userId 位<b>绝不能</b>是 {@code token:<指纹>} 形式（userId 为纯数字，天然不冲突）。
     */
    public static final String REFRESH_LOOKUP = "medi:refresh:token:";

    /** accessToken 黑名单前缀：{@code medi:blacklist:<token指纹>}（契约 §2.3） */
    public static final String BLACKLIST = "medi:blacklist:";

    /** 短信验证码前缀：{@code medi:sms:<scene>:<phone>}（契约 §2.5） */
    public static final String SMS = "medi:sms:";

    /** 短信发送间隔锁前缀：{@code medi:sms:interval:<phone>}（契约 §2.5：同号 60s 一次） */
    public static final String SMS_INTERVAL = "medi:sms:interval:";

    /** 限流计数前缀：{@code medi:rate:<限流名>:<主体>}（阶段 5 限流组件） */
    public static final String RATE = "medi:rate:";

    /** 定时任务锁前缀：{@code medi:job:<任务名>}（阶段 5 定时任务防重复执行） */
    public static final String JOB = "medi:job:";

    private CacheKeys() {
    }

    /** {@code medi:refresh:<userId>} */
    public static String refresh(Long userId) {
        return REFRESH + userId;
    }

    /** {@code medi:refresh:token:<指纹>} */
    public static String refreshLookup(String tokenFingerprint) {
        return REFRESH_LOOKUP + tokenFingerprint;
    }

    /** {@code medi:blacklist:<token指纹>} */
    public static String blacklist(String tokenFingerprint) {
        return BLACKLIST + tokenFingerprint;
    }

    /** {@code medi:sms:<scene>:<phone>} */
    public static String sms(String scene, String phone) {
        return SMS + scene + ":" + phone;
    }

    /** {@code medi:sms:interval:<phone>} */
    public static String smsInterval(String phone) {
        return SMS_INTERVAL + phone;
    }

    /** {@code medi:rate:<name>:<subject>} */
    public static String rate(String name, String subject) {
        return RATE + name + ":" + subject;
    }

    /** {@code medi:job:<name>} */
    public static String job(String name) {
        return JOB + name;
    }
}
