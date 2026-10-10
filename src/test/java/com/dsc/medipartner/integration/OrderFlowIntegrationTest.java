package com.dsc.medipartner.integration;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.order.domain.dto.CreateOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.OrderQuery;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.service.OrderService;
import com.dsc.medipartner.module.order.service.PriceService;
import com.dsc.medipartner.module.wallet.domain.vo.WalletVO;
import com.dsc.medipartner.module.wallet.service.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 资金与计价主链路集成测试（事项 3A）：连<b>真实 MySQL</b>，验证单测覆盖不到的部分。
 *
 * <p>覆盖三类单测无法发现的问题：
 * <ol>
 *   <li><b>SQL 与实体不一致</b>——列名拼错、类型不匹配，Mockito 下完全不可见</li>
 *   <li><b>数据库约束真的生效</b>——尤其 H-2 的 {@code uk_biz_no_type} 唯一键</li>
 *   <li><b>Flyway 建出的表结构与代码预期一致</b></li>
 * </ol>
 *
 * <p>运行：{@code mvn test -Pintegration}（需本机 MySQL，库 medipartner_test）。
 * 默认 profile 下这些测试被 JUnit tag 过滤掉，普通 {@code mvn test} 不受影响。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
@DisplayName("资金与计价主链路（真实 MySQL）")
class OrderFlowIntegrationTest {

    @Autowired
    private PriceService priceService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------------- 计价：V11/V16 种子数据真的能被读到 ----------------

    @Test
    @DisplayName("§5.1 全国默认规则：ACCOMPANY 4h = 200.00")
    void defaultRuleIsReadFromDatabase() {
        var price = priceService.calc("ACCOMPANY", null, new BigDecimal("4.0"));

        assertThat(price.getCity()).isEqualTo("DEFAULT");
        assertThat(price.getAmount()).isEqualByComparingTo("200.00");
        assertThat(price.getPriceVersion()).isNotBlank();
    }

    @Test
    @DisplayName("§5.1 V16 广州专属规则覆盖全国默认：220.00 > 200.00")
    void guangzhouCityRuleOverridesDefault() {
        var gz = priceService.calc("ACCOMPANY", "440100", new BigDecimal("4.0"));
        var def = priceService.calc("ACCOMPANY", null, new BigDecimal("4.0"));

        assertThat(gz.getCity()).isEqualTo("440100");
        assertThat(gz.getAmount()).isEqualByComparingTo("220.00");
        assertThat(gz.getAmount()).isGreaterThan(def.getAmount());
    }

    @Test
    @DisplayName("§5.1 V16 深圳专属规则：230.00，与广州不同")
    void shenzhenCityRuleDiffersFromGuangzhou() {
        var sz = priceService.calc("ACCOMPANY", "440300", new BigDecimal("4.0"));
        var gz = priceService.calc("ACCOMPANY", "440100", new BigDecimal("4.0"));

        assertThat(sz.getAmount()).isEqualByComparingTo("230.00");
        assertThat(sz.getAmount()).isNotEqualByComparingTo(gz.getAmount());
    }

    @Test
    @DisplayName("§5.1 未开通城市回落 DEFAULT（受控回落，不报错）")
    void unopenedCityFallsBackToDefault() {
        var price = priceService.calc("ACCOMPANY", "999999", new BigDecimal("4.0"));

        assertThat(price.getCity()).isEqualTo("DEFAULT");
        assertThat(price.getAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("§5.1 加时按 0.5h 进位：4.6h -> 230.00")
    void extraHoursRoundUpToHalfHour() {
        var price = priceService.calc("ACCOMPANY", null, new BigDecimal("4.6"));

        assertThat(price.getExtraHours()).isEqualByComparingTo("1.0");
        assertThat(price.getAmount()).isEqualByComparingTo("230.00");
    }

    @Test
    @DisplayName("§5.1 一口价服务不加价：PICKUP_REPORT 5h 仍 60.00")
    void flatPriceServiceNeverChargesExtra() {
        var price = priceService.calc("PICKUP_REPORT", null, new BigDecimal("5.0"));

        assertThat(price.getAmount()).isEqualByComparingTo("60.00");
    }

    /**
     * §5.1 未知服务类型 -> 1001。
     *
     * <p>注意：契约 §5.1 把「该服务类型未配置」（3009）与「服务类型码值非法」（1001）分列。
     * 这里传的是<b>不存在的码值</b>，属参数非法，故为 1001；3009 是码值合法但定价表缺行，见下个用例。
     */
    @Test
    @DisplayName("§5.1 非法服务类型码值 -> 1001")
    void unknownServiceTypeThrows1001() {
        assertThatThrownBy(() -> priceService.calc("NOT_EXIST", null, new BigDecimal("4.0")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR));
    }

    /**
     * §5.1 码值合法但定价表无该城市+类型规则 -> 3009。
     * 通过临时停用规则制造「未开放定价」场景，验证 3009 而非 1001。
     */
    @Test
    @DisplayName("§5.1 码值合法但未配置定价 -> 3009")
    void configuredServiceTypeWithoutRuleThrows3009() {
        jdbc.update("UPDATE service_price_rule SET enabled = 0 WHERE city_code = 'DEFAULT' "
                + "AND service_type = 'CONSULT_AGENT'");
        try {
            assertThatThrownBy(() -> priceService.calc("CONSULT_AGENT", null, new BigDecimal("2.0")))
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRICE_RULE_NOT_FOUND));
        } finally {
            jdbc.update("UPDATE service_price_rule SET enabled = 1 WHERE city_code = 'DEFAULT' "
                    + "AND service_type = 'CONSULT_AGENT'");
        }
    }

    // ---------------- 钱包：真实 SQL 与乐观锁 ----------------

    @Test
    @DisplayName("§3.6 冻结只动 frozen，balance 不变，且 VO 的 availableBalance 正确")
    void freezeOnlyMovesFrozenBucket() {
        long userId = seedWalletUser("940001", "500.00");
        String orderNo = "MPTESTFREEZE" + System.nanoTime();

        walletService.freeze(userId, orderNo, new BigDecimal("200.00"));
        WalletVO vo = walletService.getAccountVO(userId);

        assertThat(vo.getBalance()).isEqualByComparingTo("500.00");
        assertThat(vo.getFrozen()).isEqualByComparingTo("200.00");
        assertThat(vo.getAvailableBalance()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("§5.8 结算：用户扣款解冻、陪诊师入账，两条流水带相同 biz_no")
    void settleMovesMoneyAndWritesTwoTransactions() {
        long userId = seedWalletUser("940002", "500.00");
        long workerId = seedWalletUser("940003", "100.00");
        String orderNo = "MPTESTSETTLE" + System.nanoTime();

        walletService.freeze(userId, orderNo, new BigDecimal("200.00"));
        walletService.settle(orderNo, userId, workerId, new BigDecimal("200.00"));

        WalletVO user = walletService.getAccountVO(userId);
        WalletVO worker = walletService.getAccountVO(workerId);
        assertThat(user.getBalance()).isEqualByComparingTo("300.00");
        assertThat(user.getFrozen()).isEqualByComparingTo("0.00");
        assertThat(user.getTotalExpense()).isEqualByComparingTo("200.00");
        assertThat(worker.getBalance()).isEqualByComparingTo("300.00");
        assertThat(worker.getTotalIncome()).isEqualByComparingTo("200.00");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT type, biz_no FROM wallet_transaction WHERE order_no = ? ORDER BY type", orderNo);
        // FREEZE(下单) + INCOME(陪诊师) + PAY(用户) 三条，biz_no 同为订单号，靠 (biz_no,type) 区分
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.get("type"))
                .containsExactly("FREEZE", "INCOME", "PAY");
        assertThat(rows).extracting(r -> r.get("biz_no")).containsOnly(orderNo);
    }

    /**
     * H-3 核心：重复结算不二次扣款。
     * 这里在<b>服务层</b>再调一次 settle，验证 biz_no 幂等守卫真的拦住了（不靠订单状态机）。
     */
    @Test
    @DisplayName("H-3 重复调用 settle 不二次扣款（biz_no 守卫，真实唯一键）")
    void settleIsIdempotentAtDatabaseLevel() {
        long userId = seedWalletUser("940004", "500.00");
        long workerId = seedWalletUser("940005", "100.00");
        String orderNo = "MPTESTIDEM" + System.nanoTime();

        walletService.freeze(userId, orderNo, new BigDecimal("200.00"));
        walletService.settle(orderNo, userId, workerId, new BigDecimal("200.00"));
        // 故意绕过订单状态机，直接再结算一次
        walletService.settle(orderNo, userId, workerId, new BigDecimal("200.00"));
        walletService.settle(orderNo, userId, workerId, new BigDecimal("200.00"));

        assertThat(walletService.getAccountVO(userId).getBalance()).isEqualByComparingTo("300.00");
        assertThat(walletService.getAccountVO(workerId).getBalance()).isEqualByComparingTo("300.00");
        // FREEZE + PAY + INCOME = 3 条；重复结算不得新增（否则会出现第二条 PAY）
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_transaction WHERE order_no = ?", Integer.class, orderNo);
        assertThat(count).isEqualTo(3);
        Integer payCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_transaction WHERE order_no = ? AND type = 'PAY'",
                Integer.class, orderNo);
        assertThat(payCount).isEqualTo(1);
    }

    /**
     * H-2：手工插入相同 (biz_no, type) 必须被数据库唯一键拒绝。
     * 这是资金安全的最后防线，必须在真实库上验证。
     */
    @Test
    @DisplayName("H-2 uk_biz_no_type 唯一键真实拦截重复入账")
    void duplicateBizNoIsRejectedByUniqueKey() {
        long userId = seedWalletUser("940006", "100.00");
        String bizNo = "MPTESTDUP" + System.nanoTime();

        jdbc.update("INSERT INTO wallet_transaction (id,txn_no,biz_no,user_id,order_no,type,amount,"
                        + "balance_before,balance_after,frozen_before,frozen_after,remark) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                System.nanoTime(), "TXA" + System.nanoTime(), bizNo, userId, bizNo, "PAY",
                new BigDecimal("10.00"), new BigDecimal("100.00"), new BigDecimal("90.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, "first");

        // 不同 txn_no、相同 (biz_no, type) -> 必须失败
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO wallet_transaction (id,txn_no,biz_no,user_id,order_no,type,amount,"
                        + "balance_before,balance_after,frozen_before,frozen_after,remark) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                System.nanoTime(), "TXB" + System.nanoTime(), bizNo, userId, bizNo, "PAY",
                new BigDecimal("10.00"), new BigDecimal("90.00"), new BigDecimal("80.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, "duplicate"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

        // 不同类型可共存（结算时 PAY + INCOME 同 biz_no）
        jdbc.update("INSERT INTO wallet_transaction (id,txn_no,biz_no,user_id,order_no,type,amount,"
                        + "balance_before,balance_after,frozen_before,frozen_after,remark) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                System.nanoTime(), "TXC" + System.nanoTime(), bizNo, userId, bizNo, "INCOME",
                new BigDecimal("10.00"), BigDecimal.ZERO, new BigDecimal("10.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, "different type");
    }

    // ---------------- 下单：服务端算价落库 ----------------

    @Test
    @DisplayName("§5.2 下单金额来自服务端算价并落 price_version/price_rule_id")
    void createPersistsServerSidePrice() {
        long userId = seedWalletUser("940007", "500.00");
        CreateOrderRequest request = new CreateOrderRequest();
        request.setServiceType("ACCOMPANY");
        request.setSymptomDesc("集成测试：胸口疼痛需要陪诊服务");
        request.setCity("Chongqing");
        request.setServiceTime(LocalDateTime.now().plusDays(1));
        request.setHours(new BigDecimal("4.0"));

        String orderNo = createAsUser(userId, request);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT amount, price_version, price_rule_id, service_hours, order_status, city_code "
                        + "FROM service_order WHERE order_no = ?", orderNo);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("200.00");
        assertThat(row.get("price_version")).isNotNull();
        assertThat(row.get("price_rule_id")).isNotNull();
        assertThat((BigDecimal) row.get("service_hours")).isEqualByComparingTo("4.0");
        assertThat(row.get("order_status")).isEqualTo(OrderState.WAITING_ACCEPT.getCode());
    }

    /** §5.5 下单必须写入 order_status_log 首条记录（V15 表）。 */
    @Test
    @DisplayName("§5.5 下单落 order_status_log 首条 CREATE 日志")
    void createWritesInitialStatusLog() {
        long userId = seedWalletUser("940008", "500.00");
        CreateOrderRequest request = new CreateOrderRequest();
        request.setServiceType("ACCOMPANY");
        request.setSymptomDesc("集成测试：验证流转日志落库");
        request.setCity("Chongqing");
        request.setServiceTime(LocalDateTime.now().plusDays(1));

        String orderNo = createAsUser(userId, request);

        List<Map<String, Object>> logs = jdbc.queryForList(
                "SELECT to_state, event, operator_type FROM order_status_log WHERE order_no = ?", orderNo);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).get("to_state")).isEqualTo(OrderState.WAITING_ACCEPT.getCode());
        assertThat(logs.get(0).get("event")).isEqualTo("CREATE");
        assertThat(logs.get(0).get("operator_type")).isEqualTo("USER");
    }

    /** §5.7 取消订单：解冻 + 记录取消原因 + 落日志。 */
    @Test
    @DisplayName("§5.7 取消订单解冻资金并记录原因")
    void cancelUnfreezesAndRecordsReason() {
        long userId = seedWalletUser("940009", "500.00");
        CreateOrderRequest request = new CreateOrderRequest();
        request.setServiceType("ACCOMPANY");
        request.setSymptomDesc("集成测试：验证取消解冻");
        request.setCity("Chongqing");
        request.setServiceTime(LocalDateTime.now().plusDays(1));

        String orderNo = createAsUser(userId, request);
        assertThat(walletService.getAccountVO(userId).getFrozen()).isEqualByComparingTo("200.00");

        cancelAsUser(userId, orderNo, "行程有变");

        WalletVO after = walletService.getAccountVO(userId);
        assertThat(after.getFrozen()).isEqualByComparingTo("0.00");
        assertThat(after.getBalance()).isEqualByComparingTo("500.00");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT order_status, cancel_reason FROM service_order WHERE order_no = ?", orderNo);
        assertThat(row.get("order_status")).isEqualTo(OrderState.CANCELLED.getCode());
        assertThat(row.get("cancel_reason")).isEqualTo("行程有变");
    }

    // ---------------- 分页与排序白名单：真实 SQL 不报错 ----------------

    /**
     * H-9：排序白名单拼出的 ORDER BY 必须在真实 MySQL 上语法正确。
     * Mockito 下 {@code wrapper.last("ORDER BY ...")} 不会被真正执行，列名写错也发现不了。
     */
    @Test
    @DisplayName("H-9 三种白名单排序在真实 MySQL 上均可执行")
    void whitelistedSortsExecuteAgainstRealDatabase() {
        long userId = seedWalletUser("940010", "500.00");
        for (String sortBy : List.of("createdAt", "serviceTime", "amount")) {
            OrderQuery query = new OrderQuery();
            query.setSortBy(sortBy);
            query.setOrder("asc");
            assertThat(orderService.pageUserOrders(userId, query)).isNotNull();
        }
    }

    /** 非法排序值必须被回落为默认列而非拼进 SQL（真实执行验证无语法错误）。 */
    @Test
    @DisplayName("H-9 非法 sortBy 回落默认且 SQL 仍可执行")
    void illegalSortByFallsBackAndStillExecutes() {
        long userId = seedWalletUser("940011", "500.00");
        OrderQuery query = new OrderQuery();
        query.setSortBy("id; DROP TABLE service_order");

        assertThat(orderService.pageUserOrders(userId, query)).isNotNull();
        // 表还在
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() "
                        + "AND table_name = 'service_order'", Integer.class);
        assertThat(n).isEqualTo(1);
    }

    /** §3.3 各状态计数：真实 GROUP 逻辑与状态码映射。 */
    @Test
    @DisplayName("§3.3 各状态订单计数在真实库上正确")
    void statusCountsWorkAgainstRealData() {
        long userId = seedWalletUser("940012", "2000.00");
        for (int i = 0; i < 3; i++) {
            CreateOrderRequest request = new CreateOrderRequest();
            request.setServiceType("ACCOMPANY");
            request.setSymptomDesc("集成测试：状态计数 " + i);
            request.setCity("Chongqing");
            request.setServiceTime(LocalDateTime.now().plusDays(1));
            createAsUser(userId, request);
        }

        Map<String, Long> counts = orderService.countByStatusForUser(userId);

        assertThat(counts.get("waitingAccept")).isEqualTo(3L);
        assertThat(counts).containsOnlyKeys("waitingAccept", "accepted", "inService",
                "pendingConfirm", "completed", "cancelled", "abnormal");
    }

    // ---------------- 辅助 ----------------

    /**
     * 建一个带余额的钱包账户。
     *
     * <p><b>隔离策略</b>：每个用例用带随机后缀的 openid，保证拿到的是全新用户、
     * 不会看到前一次运行或其他用例残留的订单。集成测试共享同一个库，
     * 若复用固定账号，断言会被累计数据污染（曾因此出现「期望 3 条实得 6 条」）。
     */
    private long seedWalletUser(String openidSuffix, String balance) {
        String openid = "integration_" + openidSuffix + "_" + UUID.randomUUID().toString().substring(0, 8);
        long userId = 9_000_000_000L + Math.abs(openid.hashCode() % 100_000_000L);
        jdbc.update("INSERT INTO sys_user (id, openid, nickname, role_mask, status) VALUES (?,?,?,?,?)",
                userId, openid, "集成测试用户", 3, 1);
        jdbc.update("INSERT INTO wallet_account (id, user_id, balance, frozen, withdrawing, "
                        + "total_income, total_recharge, total_expense, version) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                userId + 1, userId, new BigDecimal(balance), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        return userId;
    }

    /**
     * 以指定用户身份下单。
     * {@code OrderServiceImpl.create} 从 {@code UserContext} 取用户，故需临时填充安全上下文。
     */
    private String createAsUser(long userId, CreateOrderRequest request) {
        return withUser(userId, () -> orderService.create(request));
    }

    private void cancelAsUser(long userId, String orderNo, String reason) {
        withUser(userId, () -> {
            orderService.cancel(userId, orderNo, reason);
            return null;
        });
    }

    private <T> T withUser(long userId, java.util.function.Supplier<T> action) {
        var loginUser = new com.dsc.medipartner.common.security.LoginUser(
                userId, java.util.Set.of("ROLE_USER"));
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                loginUser, null, loginUser.authorities());
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(auth);
        try {
            return action.get();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    /** 确保 V4 种子账号存在（Flyway 会灌入），用于需要固定 ID 的断言。 */
    @Test
    @DisplayName("V4/V11/V15 种子数据在真实库中就位")
    void seedDataIsPresent() {
        Integer users = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE id IN (10001,10002,10003)", Integer.class);
        Integer rules = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_price_rule", Integer.class);
        Integer dicts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_dict WHERE enabled = 1", Integer.class);

        assertThat(users).isEqualTo(3);
        assertThat(rules).isGreaterThanOrEqualTo(15);   // V11 五条 DEFAULT + V16 十条城市
        assertThat(dicts).isGreaterThanOrEqualTo(27);
    }

    /** 已存在同名 ServiceOrder 表结构含 V13/V14 补的列（Flyway 与实体一致）。 */
    @Test
    @DisplayName("V13/V14 补列在真实库中存在（实体与表结构一致）")
    void serviceOrderHasPhase15Columns() {
        List<String> cols = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'service_order'", String.class);

        assertThat(cols).contains("price_version", "price_rule_id", "service_hours",
                "contact_phone", "remark", "city_code", "cancel_reason");
    }

    /** OrderService 分页返回的实体能被正常映射（列名与实体属性一致）。 */
    @Test
    @DisplayName("订单分页实体映射正确（无列名/类型不匹配）")
    void orderPagingMapsEntityCorrectly() {
        long userId = seedWalletUser("940013", "500.00");
        CreateOrderRequest request = new CreateOrderRequest();
        request.setServiceType("ACCOMPANY");
        request.setSymptomDesc("集成测试：验证实体映射");
        request.setCity("Chongqing");
        request.setServiceTime(LocalDateTime.now().plusDays(1));
        String orderNo = createAsUser(userId, request);

        var page = orderService.pageUserOrders(userId, new OrderQuery());
        assertThat(page.getRecords()).isNotEmpty();
        ServiceOrder first = page.getRecords().stream()
                .filter(o -> orderNo.equals(o.getOrderNo()))
                .findFirst().orElseThrow();
        assertThat(first.getAmount()).isEqualByComparingTo("200.00");
        // H-12：未传 cityCode 时回落 DEFAULT，且必须落库（此前该列恒为 NULL）
        assertThat(first.getCityCode()).isEqualTo("DEFAULT");
        assertThat(first.getCreatedAt()).isNotNull();
        assertThat(first.getUpdatedAt()).isNotNull();
    }

    /** H-12：显式传 cityCode 时，下单必须按该城市计价并把城市码落库。 */
    @Test
    @DisplayName("H-12 下单传 cityCode=440100 按广州计价且 city_code 落库")
    void createWithCityCodePricesByCityAndPersistsCode() {
        long userId = seedWalletUser("940014", "500.00");
        CreateOrderRequest request = new CreateOrderRequest();
        request.setServiceType("ACCOMPANY");
        request.setSymptomDesc("集成测试：广州城市专属计价");
        request.setCity("广州市");
        request.setCityCode("440100");
        request.setServiceTime(LocalDateTime.now().plusDays(1));
        request.setHours(new BigDecimal("4.0"));

        String orderNo = createAsUser(userId, request);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT amount, city_code, city FROM service_order WHERE order_no = ?", orderNo);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("220.00");
        assertThat(row.get("city_code")).isEqualTo("440100");
        assertThat(row.get("city")).isEqualTo("广州市");
    }
}
