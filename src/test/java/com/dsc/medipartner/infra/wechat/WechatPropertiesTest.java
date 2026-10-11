package com.dsc.medipartner.infra.wechat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信开关判定测试（阶段 6）。
 *
 * <p>这段逻辑决定了「用真接口还是 mock」，判错的后果很不对称：
 * 生产环境误判为 mock 会用假 openid 建出一批<b>永远无法登录</b>的账号，
 * 且不会有任何报错。因此必须有测试钉死每种组合。
 */
class WechatPropertiesTest {

    private WechatProperties props(String mock, String appid, String secret) {
        WechatProperties p = new WechatProperties();
        p.setMock(mock);
        p.setAppid(appid);
        p.setAppsecret(secret);
        return p;
    }

    // ---------------- auto（默认） ----------------

    @Test
    void autoUsesMockWhenCredentialsMissing() {
        assertThat(props("auto", "", "").useMock()).isTrue();
        assertThat(props("auto", "wxtest", "").useMock()).isTrue();
        assertThat(props("auto", "", "secret").useMock()).isTrue();
    }

    /** auto + 凭证齐备 → 真实接口（无需手动改开关） */
    @Test
    void autoUsesRealWhenCredentialsPresent() {
        assertThat(props("auto", "wxtest", "secret").useMock()).isFalse();
    }

    /** 空白字符串不算「已配置」（环境变量未设置时通常是空串） */
    @Test
    void blankCredentialsAreTreatedAsMissing() {
        assertThat(props("auto", "   ", "   ").useMock()).isTrue();
    }

    @Test
    void nullCredentialsAreTreatedAsMissing() {
        assertThat(props("auto", null, null).useMock()).isTrue();
    }

    // ---------------- 强制模式 ----------------

    /** mock=true 时即便配了凭证也用 mock（供临时回到假数据联调） */
    @Test
    void explicitTrueForcesMockEvenWithCredentials() {
        assertThat(props("true", "wxtest", "secret").useMock()).isTrue();
    }

    /**
     * <b>mock=false 时必须用真实接口，即便缺凭证。</b>
     *
     * <p>这是刻意的「失败要响」：缺凭证会在首次调用时报明确错误，
     * 而不是悄悄降级成 mock 用假数据把业务跑通——
     * 后者会让问题推迟到上线后才被发现。
     */
    @Test
    void explicitFalseForcesRealEvenWithoutCredentials() {
        assertThat(props("false", "", "").useMock()).isFalse();
        assertThat(props("false", "wxtest", "").useMock()).isFalse();
    }

    /** 配置为强制真实却缺凭证——应能识别出这种误配 */
    @Test
    void detectsMisconfiguredStrictReal() {
        assertThat(props("false", "", "").misconfiguredStrictReal()).isTrue();
        assertThat(props("false", "wxtest", "secret").misconfiguredStrictReal()).isFalse();
        assertThat(props("auto", "", "").misconfiguredStrictReal()).isFalse();
    }

    /** 大小写不敏感（环境变量里常写 TRUE/FALSE） */
    @Test
    void modeIsCaseInsensitive() {
        assertThat(props("TRUE", "wxtest", "secret").useMock()).isTrue();
        assertThat(props("FALSE", "", "").useMock()).isFalse();
        assertThat(props("Auto", "wxtest", "secret").useMock()).isFalse();
    }

    /** 无法识别的值按 auto 处理（不因笔误导致启动异常） */
    @Test
    void unknownModeFallsBackToAuto() {
        assertThat(props("yes", "wxtest", "secret").useMock()).isFalse();
        assertThat(props("yes", "", "").useMock()).isTrue();
    }

    // ---------------- 默认值 ----------------

    @Test
    void defaultsAreSafeForDevelopment() {
        WechatProperties def = new WechatProperties();
        // 默认 auto + 无凭证 → mock，保证新克隆的仓库开箱可跑
        assertThat(def.useMock()).isTrue();
        assertThat(def.isMockPhone()).isTrue();
        assertThat(def.getBaseUrl()).isEqualTo("https://api.weixin.qq.com");
        assertThat(def.getTimeoutMs()).isPositive();
        assertThat(def.getSessionKeyTtlSeconds()).isPositive();
    }
}
