package com.dsc.medipartner.common.security;

import com.dsc.medipartner.common.cache.CacheKeys;
import com.dsc.medipartner.common.cache.CacheService;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

/**
 * Token 生命周期服务（阶段 5，契约 §2.2 / §2.3）。
 *
 * <p>负责两件 JWT 本身做不到的事：
 * <ol>
 *   <li><b>refreshToken 的签发与轮换</b>——JWT 无状态且无法吊销，因此刷新凭据必须落在
 *       可撤销的存储里。契约要求一次性轮换：每次刷新都换新值，旧值立即失效。</li>
 *   <li><b>accessToken 黑名单</b>——JWT 在过期前一直有效，若不做黑名单，
 *       「退出登录」只是前端删了本地存储，被抓到的 token 仍可继续用满 2 小时。</li>
 * </ol>
 *
 * <p><b>存的是指纹而非原值</b>：Redis 一旦泄露，存原值等于把所有用户的
 * refreshToken 直接交出；存 SHA-256 指纹则攻击者无法反推，而校验只需比对指纹。
 */
@Slf4j
@Component
public class TokenService {

    /** 刷新凭据随机字节数：32 字节 = 256 bit，暴力枚举不可行 */
    private static final int REFRESH_TOKEN_BYTES = 32;

    private final CacheService cacheService;
    private final JwtUtil jwtUtil;
    private final long refreshExpireMinutes;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenService(CacheService cacheService, JwtUtil jwtUtil,
                        @Value("${medi.jwt.refresh-expire-minutes:10080}") long refreshExpireMinutes) {
        this.cacheService = cacheService;
        this.jwtUtil = jwtUtil;
        this.refreshExpireMinutes = refreshExpireMinutes;
    }

    // ---------------- refreshToken ----------------

    /**
     * 签发新的 refreshToken（覆盖旧值，同一用户只保留一个有效凭据）。
     *
     * <p><b>为什么同时写正反两个键</b>：契约 §2.2 把刷新接口设为<b>匿名</b>
     * （凭 refreshToken 本身鉴权），请求体里没有 userId。而 refreshToken 是随机串、
     * 不含任何身份信息，无法像 JWT 那样反解出主体。因此除正向键
     * {@code medi:refresh:<userId>}（供吊销用）外，还需反向键
     * {@code medi:refresh:token:<指纹>} → userId（供刷新时按值查身份用）。
     * 两者同时写入、同时删除，任一缺失都会让刷新功能失效。
     *
     * @return 明文 refreshToken（仅此一次返回给客户端，服务端只留指纹）
     */
    public String issueRefreshToken(Long userId) {
        String raw = randomToken();
        String fp = fingerprint(raw);
        Duration ttl = Duration.ofMinutes(refreshExpireMinutes);

        // ⚠ 必须先清掉上一次的反向键，否则它会成为孤儿：
        // 用户在 7 天内反复登录时，每次登录都覆盖正向键、却把上一枚反向键留在 Redis 里，
        // 直到 TTL 到期。这些孤儿键指向的是已被替换的旧指纹，虽然因一致性校验
        // 无法通过而不可利用，但会持续占用内存（实测一次压测就积压了 3 个）。
        String previous = cacheService.get(CacheKeys.refresh(userId));
        if (previous != null) {
            cacheService.delete(CacheKeys.refreshLookup(previous));
        }

        // 正向：以用户为键，登出/封禁时按 userId 一键吊销
        cacheService.set(CacheKeys.refresh(userId), fp, ttl);
        // 反向：以指纹为键，刷新时由 token 反查 userId
        cacheService.set(CacheKeys.refreshLookup(fp), String.valueOf(userId), ttl);
        return raw;
    }

    /**
     * 由 refreshToken 反查 userId，并<b>消费</b>该 token（一次性）。
     *
     * <p>校验通过后立即删除正反两个键，保证同一个 refreshToken 不能被用第二次。
     * 这是防重放的关键：若攻击者窃取到已被正常刷新消费过的旧 token，也无法使用。
     *
     * @return userId；token 无效、过期或已被使用过时返回 {@code null}
     */
    public Long resolveUserIdByRefreshToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        String fp = fingerprint(rawToken);
        String userIdText = cacheService.get(CacheKeys.refreshLookup(fp));
        if (userIdText == null) {
            return null;
        }
        Long userId;
        try {
            userId = Long.valueOf(userIdText.trim());
        } catch (NumberFormatException e) {
            log.warn("[token] refresh 反查键内容非法，按失效处理: {}", userIdText);
            return null;
        }
        // 一致性校验：正向键必须仍指向同一指纹，否则说明已被重新签发（旧 token 应失效）
        String current = cacheService.get(CacheKeys.refresh(userId));
        if (current == null || !constantTimeEquals(current, fp)) {
            cacheService.delete(CacheKeys.refreshLookup(fp));
            return null;
        }
        // 一次性：正反两个键都要删，否则残留的反向键会在正向键重建后仍可命中
        cacheService.delete(CacheKeys.refresh(userId));
        cacheService.delete(CacheKeys.refreshLookup(fp));
        return userId;
    }

    /** 吊销该用户全部刷新凭据（登出、改密、封禁时调用） */
    public void revokeRefreshToken(Long userId) {
        // 先读指纹以便顺手清掉反向键，避免留下悬空映射
        String fp = cacheService.get(CacheKeys.refresh(userId));
        cacheService.delete(CacheKeys.refresh(userId));
        if (fp != null) {
            cacheService.delete(CacheKeys.refreshLookup(fp));
        }
    }

    /** refreshToken 有效期（秒），用于 §2.2 的 {@code expiresIn} 回显 */
    public long refreshExpireSeconds() {
        return refreshExpireMinutes * 60;
    }

    // ---------------- accessToken 黑名单 ----------------

    /**
     * 将 accessToken 加入黑名单，TTL = 该 token 的剩余有效期。
     *
     * <p><b>TTL 取剩余有效期而非固定值</b>：token 自然过期后再留在黑名单里没有意义，
     * 只会白占内存。取剩余时长可让黑名单自动收缩到「尚未过期的已登出 token」这一最小集合。
     *
     * @param token accessToken 原文
     */
    public void blacklist(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        Duration remaining = remainingValidity(token);
        if (remaining.isZero() || remaining.isNegative()) {
            // 已过期，无需拉黑
            return;
        }
        cacheService.set(CacheKeys.blacklist(fingerprint(token)), "1", remaining);
    }

    /**
     * accessToken 是否已被拉黑（登出后失效）。
     *
     * <p>由 {@code JwtAuthenticationFilter} 在每次请求时调用。
     * 缓存后端不可用时返回 false（放行）——<b>可用性优先</b>：
     * 若 Redis 故障导致所有已登录用户被踢下线，影响远大于「登出的 token 短暂仍可用」。
     * 这是刻意的取舍，运维上通过 Redis 高可用来弥补。
     */
    public boolean isBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return cacheService.exists(CacheKeys.blacklist(fingerprint(token)));
    }

    /**
     * 计算 token 剩余有效期。
     *
     * <p>解析失败（签名不对、格式错误、已过期）统一返回 {@link Duration#ZERO}，
     * 由调用方按「无需处理」对待——黑名单只服务于合法签发的 token。
     */
    private Duration remainingValidity(String token) {
        try {
            Claims claims = jwtUtil.parse(token);
            Instant expiration = claims.getExpiration().toInstant();
            return Duration.between(Instant.now(), expiration);
        } catch (Exception e) {
            return Duration.ZERO;
        }
    }

    // ---------------- 内部工具 ----------------

    /** 生成 URL 安全的无填充 Base64 随机串 */
    private String randomToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 计算 SHA-256 指纹（hex）。
     *
     * <p>用 SHA-256 而非 {@code String.hashCode()}：后者 32 位且碰撞容易构造，
     * 用作凭据存储会让攻击者能构造出与受害者同指纹的 token。
     */
    private String fingerprint(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境损坏
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 常量时间字符串比较，避免时序侧信道 */
    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** 供 §2.1 登录响应生成 accessToken（统一在此签发，避免各处重复拼 claim） */
    public String issueAccessToken(Long userId, Set<String> authorities) {
        return jwtUtil.generateToken(userId, authorities);
    }
}
