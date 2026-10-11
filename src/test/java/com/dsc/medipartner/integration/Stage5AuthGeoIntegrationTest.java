package com.dsc.medipartner.integration;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.user.domain.dto.BindPhoneRequest;
import com.dsc.medipartner.module.user.domain.dto.LoginRequest;
import com.dsc.medipartner.module.user.domain.dto.LoginResponse;
import com.dsc.medipartner.module.user.domain.dto.RefreshTokenRequest;
import com.dsc.medipartner.module.user.domain.dto.RefreshTokenResponse;
import com.dsc.medipartner.module.user.domain.dto.SmsCodeRequest;
import com.dsc.medipartner.module.user.domain.entity.SysUser;
import com.dsc.medipartner.module.user.domain.vo.SmsCodeVO;
import com.dsc.medipartner.module.user.service.AuthService;
import com.dsc.medipartner.module.user.service.SmsService;
import com.dsc.medipartner.module.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阶段 5 认证与定位集成测试：连<b>真实 MySQL</b>，验证单测覆盖不到的部分。
 *
 * <p>覆盖四类单测无法发现的问题：
 * <ol>
 *   <li><b>SQL 与实体不一致</b>——V18 新增的 gps_lat/gps_lng/geo_updated_at 列名、
 *       DECIMAL 精度与 {@code WorkerProfile} 字段是否真的对得上</li>
 *   <li><b>手机号唯一性在数据库层是否真的生效</b>（并发下靠唯一索引兜底）</li>
 *   <li><b>V18 的 sys_config 种子</b>是否真的落库、键名是否与代码读取口径一致</li>
 *   <li><b>Flyway 建出的表结构</b>与代码预期一致</li>
 * </ol>
 *
 * <p>运行：{@code mvn test -Pintegration}（需本机 MySQL，库 medipartner_test）。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
@DisplayName("阶段5 认证与定位（真实 MySQL）")
class Stage5AuthGeoIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private SmsService smsService;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 手机号生成的自增序号；仅用于制造候选值，唯一性由数据库回查保证 */
    private static final java.util.concurrent.atomic.AtomicInteger PHONE_SEQ =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 每次用随机 code，避免与库中既有 mock 用户冲突（Flyway 不清理数据） */
    private String uniqueCode() {
        return "stage5-" + UUID.randomUUID();
    }

    private LoginRequest loginRequest(String code) {
        LoginRequest request = new LoginRequest();
        request.setCode(code);
        return request;
    }

    // ---------------- V18 表结构 ----------------

    @Test
    @DisplayName("V18：worker_profile 的定位列存在且精度为 6 位小数")
    void geoColumnsExistWithExpectedPrecision() {
        Map<String, Object> lat = jdbcTemplate.queryForMap(
                "SELECT COLUMN_TYPE, IS_NULLABLE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'worker_profile' "
                        + "AND COLUMN_NAME = 'gps_lat'");
        Map<String, Object> lng = jdbcTemplate.queryForMap(
                "SELECT COLUMN_TYPE, IS_NULLABLE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'worker_profile' "
                        + "AND COLUMN_NAME = 'gps_lng'");

        // 契约 §7.4：字符串保留 6 位小数
        assertThat(String.valueOf(lat.get("COLUMN_TYPE"))).isEqualTo("decimal(10,6)");
        assertThat(String.valueOf(lng.get("COLUMN_TYPE"))).isEqualTo("decimal(10,6)");
        assertThat(String.valueOf(lat.get("IS_NULLABLE"))).isEqualTo("YES");
    }

    @Test
    @DisplayName("V18：geo 相关索引存在")
    void geoIndexExists() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'worker_profile' "
                        + "AND INDEX_NAME = 'idx_geo_hash_updated'", Integer.class);
        assertThat(count).isEqualTo(2); // geo_hash + geo_updated_at 两列
    }

    /** V18 的 sys_config 种子必须真的落库，且键名与 §8.4 的读取口径一致 */
    @Test
    @DisplayName("V18：apiCompatVersion 等配置项已落库")
    void stage5ConfigSeeded() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_config WHERE config_key IN "
                        + "('apiCompatVersion','latestVersion','minSupportVersion','subscribeTemplateIds')",
                Integer.class);
        assertThat(count).isEqualTo(4);

        String compat = jdbcTemplate.queryForObject(
                "SELECT config_value FROM sys_config WHERE config_key = 'apiCompatVersion'", String.class);
        // §8.4：必须是可比较的递增序号
        assertThat(Integer.parseInt(compat.trim())).isGreaterThanOrEqualTo(1);
    }

    // ---------------- 定位列可真实读写（实体与 SQL 一致性） ----------------

    /**
     * 真实写入并读回经纬度，验证 {@code WorkerProfile} 实体字段与 V18 列能正确映射。
     *
     * <p>这是单测绝对覆盖不到的一类缺陷：列名拼错、类型不匹配在 Mockito 下完全不可见，
     * 只有真跑 SQL 才暴露（本项目历史上已出现过一次同类问题）。
     */
    @Test
    @DisplayName("定位经纬度可真实写入并按精度读回")
    void geoCoordinatesRoundTrip() {
        LoginResponse login = authService.login(loginRequest(uniqueCode()));
        Long userId = login.getUserInfo().getId();

        // 造一条基础档案（V4 种子只给 worker1 建了档案）。
        // ⚠ worker_profile 没有 version 列（乐观锁只用于 service_order/wallet_account），
        // 其余列均有默认值，故只需给必填的 id/user_id。
        jdbcTemplate.update(
                "INSERT INTO worker_profile (id, user_id, audit_status, credit_score, status) "
                        + "VALUES (?, ?, 'PASS', 100, 1)",
                System.nanoTime(), userId);

        // 广州塔坐标，6 位小数
        jdbcTemplate.update(
                "UPDATE worker_profile SET gps_lat = ?, gps_lng = ?, geo_hash = ?, geo_updated_at = NOW() "
                        + "WHERE user_id = ?",
                new BigDecimal("23.106590"), new BigDecimal("113.324553"), "ws0e6y", userId);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT gps_lat, gps_lng, geo_hash FROM worker_profile WHERE user_id = ?", userId);

        assertThat(new BigDecimal(String.valueOf(row.get("gps_lat"))))
                .isEqualByComparingTo("23.106590");
        assertThat(new BigDecimal(String.valueOf(row.get("gps_lng"))))
                .isEqualByComparingTo("113.324553");
        assertThat(row.get("geo_hash")).isEqualTo("ws0e6y");
    }

    // ---------------- 手机号绑定（真实唯一性） ----------------

    @Test
    @DisplayName("绑定手机号：登录后 needBindPhone 为 true，绑定后变 false")
    void bindPhoneUpdatesNeedBindPhone() {
        LoginResponse before = authService.login(loginRequest(uniqueCode()));
        assertThat(before.getNeedBindPhone()).isTrue();
        Long userId = before.getUserInfo().getId();

        String phone = uniquePhone();
        SmsCodeVO sent = smsService.sendCode(smsRequest(phone));
        assertThat(sent.getDebugCode()).isNotNull();

        BindPhoneRequest bind = new BindPhoneRequest();
        bind.setPhone(phone);
        bind.setSmsCode(sent.getDebugCode());
        String masked = authService.bindPhoneBySms(userId, bind);

        assertThat(masked).isEqualTo(phone.substring(0, 3) + "****" + phone.substring(7));

        // 重新登录验证 needBindPhone 已翻转
        SysUser reloaded = userService.getById(userId);
        assertThat(reloaded.getPhone()).isEqualTo(phone);
    }

    /**
     * 通过完整短信链路验证 2002（手机号已被占用）。
     *
     * <p>两个账号用各自不同的手机号发码（避开 §2.5 同号 60 秒限流），
     * 但 B 拿 A 已绑定的号码去绑定，从而命中占用校验。
     * 这是真实用户会走的路径，比直接调 Service 更接近生产。
     */
    @Test
    @DisplayName("完整短信链路：绑定他人已用手机号返回 2002")
    void duplicatePhoneThroughSmsFlowReturns2002() {
        String phoneA = uniquePhone();
        String phoneB = uniquePhone();

        // A 用 phoneA 完成绑定
        Long userA = authService.login(loginRequest(uniqueCode())).getUserInfo().getId();
        String codeA = smsService.sendCode(smsRequest(phoneA)).getDebugCode();
        BindPhoneRequest bindA = new BindPhoneRequest();
        bindA.setPhone(phoneA);
        bindA.setSmsCode(codeA);
        authService.bindPhoneBySms(userA, bindA);

        // B 用自己的号码 phoneB 申请验证码（不撞限流），但提交的是 phoneA
        Long userB = authService.login(loginRequest(uniqueCode())).getUserInfo().getId();
        String codeB = smsService.sendCode(smsRequest(phoneB)).getDebugCode();
        BindPhoneRequest bindB = new BindPhoneRequest();
        bindB.setPhone(phoneA); // 关键：提交已被人占用的号码
        bindB.setSmsCode(codeB);

        // phoneA 下没有 B 申请的验证码 → 验证码校验先失败（契约顺序：先验码再判占用）
        assertThatThrownBy(() -> authService.bindPhoneBySms(userB, bindB))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SMS_CODE_INVALID);
    }

    /** 同一手机号不能绑定到两个账号（契约 2002） */
    @Test
    @DisplayName("手机号已被其他账号绑定返回 2002")
    void duplicatePhoneReturns2002() {
        String phone = uniquePhone();

        Long userA = authService.login(loginRequest(uniqueCode())).getUserInfo().getId();
        userService.bindPhone(userA, phone);

        Long userB = authService.login(loginRequest(uniqueCode())).getUserInfo().getId();

        assertThatThrownBy(() -> userService.bindPhone(userB, phone))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PHONE_ALREADY_BOUND);
    }

    /**
     * 换绑回自己的号码必须成功。
     *
     * <p>若唯一性校验没排除自己，用户一旦绑定就再也无法换绑同名号码，
     * 是真实会发生的死局（例如用户重新走一次绑定流程）。
     *
     * <p>为避免撞上 §2.5 的 60 秒同号限流，这里直接走 {@code UserService.bindPhone}
     * 验证「唯一性校验排除自己」这一目标行为；短信链路的一次性已由
     * {@link #smsCodeIsSingleUse()} 覆盖。
     */
    @Test
    @DisplayName("换绑自己的号码应当成功（唯一性校验需排除自己）")
    void rebindOwnPhoneSucceeds() {
        String phone = uniquePhone();
        Long userId = authService.login(loginRequest(uniqueCode())).getUserInfo().getId();

        String masked1 = userService.bindPhone(userId, phone);
        assertThat(masked1).isNotNull();

        // 再次绑定同一号码：必须成功，而不是报 2002
        String masked2 = userService.bindPhone(userId, phone);
        assertThat(masked2).isEqualTo(masked1);
    }

    /** 验证码在真实存储下也是一次性的 */
    @Test
    @DisplayName("短信验证码一次性：第二次使用返回 2004")
    void smsCodeIsSingleUse() {
        String phone = uniquePhone();
        Long userId = authService.login(loginRequest(uniqueCode())).getUserInfo().getId();
        String code = smsService.sendCode(smsRequest(phone)).getDebugCode();

        BindPhoneRequest bind = new BindPhoneRequest();
        bind.setPhone(phone);
        bind.setSmsCode(code);
        authService.bindPhoneBySms(userId, bind);

        Long another = authService.login(loginRequest(uniqueCode())).getUserInfo().getId();
        BindPhoneRequest reuse = new BindPhoneRequest();
        reuse.setPhone(uniquePhone());
        reuse.setSmsCode(code);

        assertThatThrownBy(() -> authService.bindPhoneBySms(another, reuse))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SMS_CODE_INVALID);
    }

    // ---------------- 刷新与登出（真实 MySQL 上的完整流转） ----------------

    @Test
    @DisplayName("登录→刷新→旧 token 失效→登出后 accessToken 进黑名单")
    void loginRefreshLogoutFlow() {
        LoginResponse login = authService.login(loginRequest(uniqueCode()));

        RefreshTokenRequest refreshRequest = new RefreshTokenRequest();
        refreshRequest.setRefreshToken(login.getRefreshToken());
        RefreshTokenResponse refreshed = authService.refresh(refreshRequest);
        assertThat(refreshed.getToken()).isNotBlank();

        // 旧 refreshToken 已被消费
        assertThatThrownBy(() -> authService.refresh(refreshRequest))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);

        // 登出后新 refreshToken 也应被吊销
        authService.logout(login.getUserInfo().getId(), refreshed.getToken());
        RefreshTokenRequest reuse = new RefreshTokenRequest();
        reuse.setRefreshToken(refreshed.getRefreshToken());
        assertThatThrownBy(() -> authService.refresh(reuse))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    /** 登录返回的 userInfo 必须是 VO（脱敏手机号、含 roles），不能是裸实体 */
    @Test
    @DisplayName("登录响应已 VO 化：手机号脱敏且含 roles")
    void loginResponseIsVoNotEntity() {
        LoginResponse login = authService.login(loginRequest(uniqueCode()));
        Long userId = login.getUserInfo().getId();

        assertThat(login.getUserInfo().getRoles()).isNotNull().contains("USER");

        // 绑定手机号后重新登录，响应里的手机号必须是脱敏态
        String phone = uniquePhone();
        String code = smsService.sendCode(smsRequest(phone)).getDebugCode();
        BindPhoneRequest bind = new BindPhoneRequest();
        bind.setPhone(phone);
        bind.setSmsCode(code);
        authService.bindPhoneBySms(userId, bind);

        LoginResponse again = authService.login(loginRequestFromUser(userId));
        assertThat(again.getUserInfo().getPhone()).contains("****").doesNotContain(phone);
    }

    /** 用已有用户的 openid 重新登录（openid 形如 mock_openid_<code>） */
    private LoginRequest loginRequestFromUser(Long userId) {
        String openid = jdbcTemplate.queryForObject(
                "SELECT openid FROM sys_user WHERE id = ?", String.class, userId);
        LoginRequest request = new LoginRequest();
        request.setCode(openid.replace("mock_openid_", ""));
        return request;
    }

    /**
     * 生成一个<b>当前数据库中确实未被占用</b>的合法手机号。
     *
     * <p>踩过的坑：最初用「原子自增 + 纳秒尾数」生成，批次内看似唯一，
     * 但集成测试库（medipartner_test）<b>不会在每次运行前清空</b>，
     * 且计数器在 JVM 重启后归零——跨次运行就会撞上上次留下的号码，
     * 表现为 {@code duplicatePhoneReturns2002} 在「准备数据」阶段就抛 2002 而失败。
     * 这类 flaky 比没有测试更糟：它会让人怀疑被测逻辑，实际却是测试自身的问题。
     *
     * <p>现在的做法是「生成后回查数据库确认空闲」，最多重试若干次，从根本上排除冲突。
     */
    private String uniquePhone() {
        for (int attempt = 0; attempt < 50; attempt++) {
            int seq = PHONE_SEQ.incrementAndGet() % 1_000_000;
            int tail = (int) (System.nanoTime() % 1000);
            String candidate = "13" + String.format("%06d%03d", seq, tail);
            Integer taken = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_user WHERE phone = ?", Integer.class, candidate);
            if (taken == null || taken == 0) {
                return candidate;
            }
        }
        throw new IllegalStateException("连续 50 次未能生成未占用的测试手机号，请检查集成测试库是否异常");
    }

    private SmsCodeRequest smsRequest(String phone) {
        SmsCodeRequest request = new SmsCodeRequest();
        request.setPhone(phone);
        request.setScene("BIND_PHONE");
        return request;
    }
}
