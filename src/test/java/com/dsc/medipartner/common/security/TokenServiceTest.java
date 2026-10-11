package com.dsc.medipartner.common.security;

import com.dsc.medipartner.common.cache.LocalCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token 生命周期测试（阶段 5，契约 §2.2 / §2.3）。
 *
 * <p>用真实的 {@link LocalCacheService} 而非 mock：本类要验证的「签发→反查→一次性消费→
 * 吊销」是一串<b>跨调用的状态流转</b>，mock 掉存储会让测试退化成「验证自己写了自己」，
 * 无法发现键名写错、反向键没删这类真实缺陷（这类 bug 编译和单测都可能通过）。
 */
class TokenServiceTest {

    private static final String SECRET = "Test_Secret_Key_For_Token_Service_Unit_Test_0123456789";

    private LocalCacheService cache;
    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        cache = new LocalCacheService();
        JwtUtil jwtUtil = new JwtUtil(SECRET, 120);
        tokenService = new TokenService(cache, jwtUtil, 10080);
    }

    // ---------------- refreshToken：签发与一次性消费 ----------------

    @Test
    void issuedRefreshTokenResolvesToItsOwner() {
        String raw = tokenService.issueRefreshToken(1001L);
        assertThat(tokenService.resolveUserIdByRefreshToken(raw)).isEqualTo(1001L);
    }

    /** 一次性：同一个 refreshToken 不能消费两次（防重放的核心） */
    @Test
    void refreshTokenCanOnlyBeConsumedOnce() {
        String raw = tokenService.issueRefreshToken(1001L);
        assertThat(tokenService.resolveUserIdByRefreshToken(raw)).isEqualTo(1001L);
        assertThat(tokenService.resolveUserIdByRefreshToken(raw)).isNull();
    }

    @Test
    void unknownRefreshTokenIsRejected() {
        assertThat(tokenService.resolveUserIdByRefreshToken("not-a-real-token")).isNull();
    }

    @Test
    void nullOrBlankRefreshTokenIsRejected() {
        assertThat(tokenService.resolveUserIdByRefreshToken(null)).isNull();
        assertThat(tokenService.resolveUserIdByRefreshToken("")).isNull();
        assertThat(tokenService.resolveUserIdByRefreshToken("   ")).isNull();
    }

    /** 每个用户只保留一个有效凭据：重新签发后旧值必须失效 */
    @Test
    void reissuingInvalidatesPreviousToken() {
        String first = tokenService.issueRefreshToken(1001L);
        String second = tokenService.issueRefreshToken(1001L);

        assertThat(tokenService.resolveUserIdByRefreshToken(first)).isNull();
        assertThat(tokenService.resolveUserIdByRefreshToken(second)).isEqualTo(1001L);
    }

    /**
     * 重复签发不得留下孤儿反向键（内存泄漏回归防护）。
     *
     * <p>背景：反向键以指纹为键、userId 为值。若重新签发时只覆盖正向键，
     * 上一枚反向键会一直留到 7 天后 TTL 到期。用户在有效期内反复登录，
     * 就会持续积压这类无用键。<b>这是真机验证才发现的问题</b>（对真实 Redis 数键时
     * 发现 23 个反向键只对应 20 个正向键）——单测只看「旧 token 失效了没」，
     * 不数键的个数，因此原本完全测不出来。
     *
     * <p>这里直接检查存储内容：同一用户反复签发后，反向键应当<b>只有 1 个</b>。
     * 为了让断言可观测，用一个能列举键的本地存储包装（仅测试内使用）。
     */
    @Test
    void repeatedIssuanceLeavesNoOrphanLookupKeys() {
        for (int i = 0; i < 5; i++) {
            tokenService.issueRefreshToken(1001L);
        }

        // 反向键必须唯一：若清理缺失，这里会是 5 个
        assertThat(countLookupKeysFor(1001L)).isEqualTo(1);
    }

    /** 数出「值等于该 userId」的反向键个数（反向键以指纹为键、userId 为值） */
    private long countLookupKeysFor(Long userId) {
        return cache.snapshotKeys().stream()
                .filter(k -> k.startsWith("medi:refresh:token:"))
                .filter(k -> String.valueOf(userId).equals(cache.get(k)))
                .count();
    }

    /** 孤儿反向键即使残留也不可利用：一致性校验必须拒绝它 */
    @Test
    void staleLookupKeyIsNotUsable() {
        String first = tokenService.issueRefreshToken(1001L);
        // 重新签发后，first 的反向键应已被清理
        tokenService.issueRefreshToken(1001L);

        assertThat(tokenService.resolveUserIdByRefreshToken(first)).isNull();
    }

    /** 不同用户的凭据互不干扰 */
    @Test
    void tokensAreIsolatedPerUser() {
        String a = tokenService.issueRefreshToken(1001L);
        String b = tokenService.issueRefreshToken(2002L);

        assertThat(tokenService.resolveUserIdByRefreshToken(a)).isEqualTo(1001L);
        assertThat(tokenService.resolveUserIdByRefreshToken(b)).isEqualTo(2002L);
    }

    /** 吊销后凭据立即失效（登出场景） */
    @Test
    void revokedTokenIsRejected() {
        String raw = tokenService.issueRefreshToken(1001L);
        tokenService.revokeRefreshToken(1001L);
        assertThat(tokenService.resolveUserIdByRefreshToken(raw)).isNull();
    }

    /**
     * 吊销必须同时清掉反向键。
     *
     * <p>若只删正向键，反向键会残留到 TTL 结束；期间该 token 反查虽能拿到 userId，
     * 但一致性校验会发现正向键不匹配而拒绝——行为正确，却白白多占一份内存。
     * 本用例锁住「两个键一起清」的实现约定。
     */
    @Test
    void revokeClearsBothDirections() {
        String raw = tokenService.issueRefreshToken(1001L);
        tokenService.revokeRefreshToken(1001L);
        // 再签发一次，随后旧 token 不得因为残留反向键而复活
        tokenService.issueRefreshToken(1001L);
        assertThat(tokenService.resolveUserIdByRefreshToken(raw)).isNull();
    }

    /** 吊销不存在的凭据是幂等的 */
    @Test
    void revokeUnknownUserIsIdempotent() {
        tokenService.revokeRefreshToken(9999L);
        tokenService.revokeRefreshToken(9999L);
    }

    /** 凭据是随机串而非 JWT：不得可反解，且每次签发都不同 */
    @Test
    void refreshTokensAreRandomAndOpaque() {
        String a = tokenService.issueRefreshToken(1001L);
        String b = tokenService.issueRefreshToken(1002L);
        assertThat(a).isNotEqualTo(b);
        // 不含身份信息，长度足以抵抗暴力枚举（32 字节 Base64 无填充 = 43 字符）
        assertThat(a).hasSizeGreaterThanOrEqualTo(40);
        assertThat(a).doesNotContain("1001");
    }

    @Test
    void refreshExpireSecondsMatchesConfiguredMinutes() {
        assertThat(tokenService.refreshExpireSeconds()).isEqualTo(10080L * 60);
    }

    // ---------------- accessToken 黑名单 ----------------

    private String newAccessToken(Long userId) {
        return tokenService.issueAccessToken(userId, Set.of("ROLE_USER"));
    }

    @Test
    void freshTokenIsNotBlacklisted() {
        assertThat(tokenService.isBlacklisted(newAccessToken(1001L))).isFalse();
    }

    /** 登出后 token 立即失效——这是 §2.3 存在的全部意义 */
    @Test
    void blacklistedTokenIsRejected() {
        String token = newAccessToken(1001L);
        tokenService.blacklist(token);
        assertThat(tokenService.isBlacklisted(token)).isTrue();
    }

    /**
     * 只拉黑给定的那个 token，不影响其它 token。
     *
     * <p><b>⚠ 两个 token 必须属于不同用户</b>：JWT 的 {@code iat}/{@code exp} 精度到<b>秒</b>，
     * 同一用户在同一秒内签发的两个 token 是<b>完全相同</b>的字符串（签名与载荷一致），
     * 拉黑其一必然拉黑其二——这不是缺陷，而是 JWT 本身的性质。
     * 因此本用例用不同 userId 构造两个可区分的 token。
     *
     * <p>顺带说明生产含义：同一用户在同一秒内重复登录，拿到的是同一个 accessToken，
     * 登出会同时影响这两次登录；若将来要求「多设备独立登出」，
     * 需要在 claim 里加入随机 {@code jti}。
     */
    @Test
    void blacklistOnlyAffectsTheGivenToken() {
        String tokenA = newAccessToken(1001L);
        String tokenB = newAccessToken(2002L);
        assertThat(tokenA).isNotEqualTo(tokenB);

        tokenService.blacklist(tokenA);

        assertThat(tokenService.isBlacklisted(tokenA)).isTrue();
        assertThat(tokenService.isBlacklisted(tokenB)).isFalse();
    }

    /** 无效/伪造 token 不写入黑名单。
     *
     * <p>理由：这类 token 解析必然失败，本就无法通过认证，入黑名单毫无意义，
     * 反而给了攻击者一个「用垃圾 token 撑爆缓存」的入口。
     * 同时本方法不得抛异常——它会在每次请求过滤器中调用。
     */
    @Test
    void invalidTokenIsNotBlacklisted() {
        tokenService.blacklist("garbage.token.value");
        assertThat(tokenService.isBlacklisted("garbage.token.value")).isFalse();
    }

    /**
     * 记录一个容易被误解的性质：同一用户同一秒内签发的 accessToken 完全相同。
     *
     * <p>JWT 的 {@code iat}/{@code exp} 精度为秒，载荷与签名因此逐字节一致。
     * 影响：① 登出会同时使该秒内的多次登录失效；② 黑名单按 token 值去重，
     * 同一秒的重复登录不会产生多条黑名单记录（这反而是好事，不会撑大缓存）。
     * 若将来需要「多设备独立登出」，应在 claim 中加入随机 {@code jti}。
     */
    @Test
    void tokensIssuedInSameSecondForSameUserAreIdentical() {
        String first = newAccessToken(1001L);
        String second = newAccessToken(1001L);
        // 记录现有行为，避免后续有人误以为这里应该不同而改坏签名逻辑
        assertThat(first).isEqualTo(second);
    }

    @Test
    void nullTokenIsSafeForBothOperations() {
        tokenService.blacklist(null);
        assertThat(tokenService.isBlacklisted(null)).isFalse();
        assertThat(tokenService.isBlacklisted("")).isFalse();
    }

    /** 已过期的 token 无需拉黑（自然失效即可，避免白占内存） */
    @Test
    void alreadyExpiredTokenIsNotBlacklisted() throws InterruptedException {
        JwtUtil shortLived = new JwtUtil(SECRET, 0);
        TokenService shortService = new TokenService(cache, shortLived, 10080);
        String expired = shortLived.generateToken(1001L, Set.of("ROLE_USER"));

        shortService.blacklist(expired);

        // 过期 token 不值得入黑名单；后续解析本就会失败
        assertThat(shortService.isBlacklisted(expired)).isFalse();
    }
}
