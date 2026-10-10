package com.dsc.medipartner.integration;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.wallet.domain.dto.RechargeCallbackRequest;
import com.dsc.medipartner.module.wallet.domain.dto.RechargeRequest;
import com.dsc.medipartner.module.wallet.domain.dto.WithdrawApplyRequest;
import com.dsc.medipartner.module.wallet.domain.dto.WithdrawQuery;
import com.dsc.medipartner.module.wallet.domain.vo.EarningVO;
import com.dsc.medipartner.module.wallet.domain.vo.RechargeCallbackVO;
import com.dsc.medipartner.module.wallet.domain.vo.RechargeVO;
import com.dsc.medipartner.module.wallet.domain.vo.WalletVO;
import com.dsc.medipartner.module.wallet.domain.vo.WithdrawVO;
import com.dsc.medipartner.module.wallet.service.EarningService;
import com.dsc.medipartner.module.wallet.service.RechargeService;
import com.dsc.medipartner.module.wallet.service.WalletService;
import com.dsc.medipartner.module.wallet.service.WithdrawService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阶段 4 资金闭环集成测试（真实 MySQL）：充值、提现、收益。
 *
 * <p>重点验证<b>单测覆盖不到的部分</b>：
 * <ol>
 *   <li>充值的<b>重复回调幂等</b>——只入账一次（§3.9 核心风险）</li>
 *   <li>提现的<b>单笔处理中约束</b>（4007）与可用余额口径（用 balance - frozen 而非 balance）</li>
 *   <li>流水 {@code biz_no} 幂等键对提现/充值同样生效（V12 的 uk_biz_no_type）</li>
 *   <li>收益统计与钱包累计列口径一致</li>
 *   <li>并发下 {@code uk_withdraw_no} / {@code uk_biz_no_type} 真的能兜住</li>
 * </ol>
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
@DisplayName("阶段4 资金闭环（真实 MySQL）")
class Phase4FundFlowIntegrationTest {

    @Autowired
    private RechargeService rechargeService;

    @Autowired
    private WithdrawService withdrawService;

    @Autowired
    private EarningService earningService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------------- §3.8 / §3.9 充值 ----------------

    @Test
    @DisplayName("§3.8/§3.9 充值成功：余额增加并写一条 RECHARGE 流水")
    void rechargeSuccessCreditsBalanceAndWritesTxn() {
        long userId = seedUser("rc1", "100.00");

        RechargeVO created = rechargeService.create(userId, rechargeRequest("200.00"));
        assertThat(created.getRechargeNo()).startsWith("RC");
        assertThat(created.getStatus()).isEqualTo("PENDING");
        assertThat(created.getPayParams()).containsEntry("mock", true);
        assertThat(created.getExpireAt()).isNotNull();

        RechargeCallbackVO result = rechargeService.callback(userId, callback(created.getRechargeNo(), "SUCCESS"));

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getBalance()).isEqualByComparingTo("300.00");
        assertThat(result.getTxnNo()).startsWith("TX");
        assertThat(walletService.getAccountVO(userId).getTotalRecharge()).isEqualByComparingTo("200.00");

        List<Map<String, Object>> txns = jdbc.queryForList(
                "SELECT type, biz_no, amount FROM wallet_transaction WHERE user_id = ?", userId);
        assertThat(txns).hasSize(1);
        assertThat(txns.get(0).get("type")).isEqualTo("RECHARGE");
        assertThat(txns.get(0).get("biz_no")).isEqualTo(created.getRechargeNo());
    }

    /**
     * §3.9 核心风险：重复回调<b>不得重复入账</b>。
     * 前端误重试或用户连点都会触发，必须靠状态机 + biz_no 唯一键双保险挡住。
     */
    @Test
    @DisplayName("§3.9 重复回调只入账一次（幂等）")
    void repeatedCallbackCreditsOnlyOnce() {
        long userId = seedUser("rc2", "0.00");
        String rechargeNo = rechargeService.create(userId, rechargeRequest("500.00")).getRechargeNo();

        rechargeService.callback(userId, callback(rechargeNo, "SUCCESS"));
        rechargeService.callback(userId, callback(rechargeNo, "SUCCESS"));
        rechargeService.callback(userId, callback(rechargeNo, "SUCCESS"));

        assertThat(walletService.getAccountVO(userId).getBalance()).isEqualByComparingTo("500.00");
        Integer txnCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_transaction WHERE user_id = ?", Integer.class, userId);
        assertThat(txnCount).isEqualTo(1);
        // 充值单也只有一条，且已置 SUCCESS
        Map<String, Object> order = jdbc.queryForMap(
                "SELECT status, txn_no FROM recharge_order WHERE recharge_no = ?", rechargeNo);
        assertThat(order.get("status")).isEqualTo("SUCCESS");
        assertThat(order.get("txn_no")).isNotNull();
    }

    @Test
    @DisplayName("§3.9 回调 FAILED：不入账，余额不变")
    void failedCallbackDoesNotCredit() {
        long userId = seedUser("rc3", "50.00");
        String rechargeNo = rechargeService.create(userId, rechargeRequest("100.00")).getRechargeNo();

        RechargeCallbackVO result = rechargeService.callback(userId, callback(rechargeNo, "FAILED"));

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getBalance()).isNull();
        assertThat(walletService.getAccountVO(userId).getBalance()).isEqualByComparingTo("50.00");
        Integer txnCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_transaction WHERE user_id = ?", Integer.class, userId);
        assertThat(txnCount).isZero();
    }

    @Test
    @DisplayName("§3.8 充值金额越界 -> 4006")
    void rechargeAmountOutOfRangeThrows4006() {
        long userId = seedUser("rc4", "0.00");

        assertThatThrownBy(() -> rechargeService.create(userId, rechargeRequest("0.001")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RECHARGE_AMOUNT_ILLEGAL));
        assertThatThrownBy(() -> rechargeService.create(userId, rechargeRequest("99999.00")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RECHARGE_AMOUNT_ILLEGAL));
    }

    @Test
    @DisplayName("§3.9 回调他人工单 -> 3001")
    void callbackOtherUsersRechargeThrows3001() {
        long owner = seedUser("rc5", "0.00");
        long intruder = seedUser("rc6", "0.00");
        String rechargeNo = rechargeService.create(owner, rechargeRequest("100.00")).getRechargeNo();

        assertThatThrownBy(() -> rechargeService.callback(intruder, callback(rechargeNo, "SUCCESS")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
    }

    // ---------------- §7.18 / §7.19 提现 ----------------

    @Test
    @DisplayName("§7.18 提现申请：扣 balance、累加 withdrawing、写 WITHDRAW 流水")
    void withdrawApplyMovesMoneyToWithdrawing() {
        long workerId = seedUser("wd1", "500.00");

        WithdrawVO vo = withdrawService.apply(workerId, withdrawRequest("100.00", "WECHAT"));

        assertThat(vo.getWithdrawNo()).startsWith("WD");
        assertThat(vo.getStatus()).isEqualTo("PENDING");
        assertThat(vo.getFee()).isEqualByComparingTo("0.00");
        assertThat(vo.getActualAmount()).isEqualByComparingTo("100.00");

        WalletVO account = walletService.getAccountVO(workerId);
        assertThat(account.getBalance()).isEqualByComparingTo("400.00");
        assertThat(account.getWithdrawing()).isEqualByComparingTo("100.00");

        List<Map<String, Object>> txns = jdbc.queryForList(
                "SELECT type, biz_no FROM wallet_transaction WHERE user_id = ?", workerId);
        assertThat(txns).hasSize(1);
        assertThat(txns.get(0).get("type")).isEqualTo("WITHDRAW");
        assertThat(txns.get(0).get("biz_no")).isEqualTo(vo.getWithdrawNo());
    }

    /** §7.18 备注 3：同一时刻只允许一笔处理中（4007）。 */
    @Test
    @DisplayName("§7.18 已有处理中提现时再次申请 -> 4007")
    void secondWithdrawWhilePendingThrows4007() {
        long workerId = seedUser("wd2", "500.00");
        withdrawService.apply(workerId, withdrawRequest("100.00", "WECHAT"));

        assertThatThrownBy(() -> withdrawService.apply(workerId, withdrawRequest("50.00", "WECHAT")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WITHDRAW_IN_PROGRESS));

        // 失败不应产生任何副作用
        assertThat(walletService.getAccountVO(workerId).getBalance()).isEqualByComparingTo("400.00");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM withdraw_apply WHERE worker_id = ?", Integer.class, workerId);
        assertThat(count).isEqualTo(1);
    }

    /**
     * 提现只能用「可用余额」= balance - frozen。
     * 这是关键安全口径：被订单冻结的钱不能提走（否则订单结算时余额会变负）。
     */
    @Test
    @DisplayName("§7.18 可用余额口径：被冻结的钱不可提现 -> 4001")
    void withdrawCannotUseFrozenFunds() {
        long workerId = seedUser("wd3", "300.00");
        // 冻结 250，可用仅 50。注意 order_no 列是 VARCHAR(32)：
        // 真实订单号形如 MP + 14 位时间 + 6 位随机 = 22 字符，测试也必须用同量级长度。
        walletService.freeze(workerId, shortOrderNo("WD"), new BigDecimal("250.00"));

        assertThatThrownBy(() -> withdrawService.apply(workerId, withdrawRequest("100.00", "WECHAT")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WALLET_BALANCE_NOT_ENOUGH));

        // 恰好等于可用额度的可以提
        WithdrawVO ok = withdrawService.apply(workerId, withdrawRequest("50.00", "WECHAT"));
        assertThat(ok.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("§7.18 提现低于下限/超上限 -> 4003")
    void withdrawAmountOutOfRangeThrows4003() {
        long workerId = seedUser("wd4", "9000.00");

        assertThatThrownBy(() -> withdrawService.apply(workerId, withdrawRequest("9.99", "WECHAT")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WITHDRAW_AMOUNT_ILLEGAL));
        assertThatThrownBy(() -> withdrawService.apply(workerId, withdrawRequest("5000.01", "WECHAT")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WITHDRAW_AMOUNT_ILLEGAL));
    }

    /** BANK 为预留渠道，契约 §7.18 明确传入返回 1001。 */
    @Test
    @DisplayName("§7.18 预留渠道 BANK -> 1001")
    void bankChannelIsRejectedWith1001() {
        long workerId = seedUser("wd5", "500.00");

        assertThatThrownBy(() -> withdrawService.apply(workerId, withdrawRequest("100.00", "BANK")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR));
    }

    @Test
    @DisplayName("§7.19 提现记录：默认倒序且可按状态筛选")
    void withdrawListFiltersByStatus() {
        long workerId = seedUser("wd6", "500.00");
        String withdrawNo = withdrawService.apply(workerId, withdrawRequest("100.00", "WECHAT")).getWithdrawNo();

        WithdrawQuery all = new WithdrawQuery();
        assertThat(withdrawService.pageWithdraws(workerId, all).getRecords()).hasSize(1);

        WithdrawQuery pending = new WithdrawQuery();
        pending.setStatus("PENDING");
        assertThat(withdrawService.pageWithdraws(workerId, pending).getRecords()).hasSize(1);

        WithdrawQuery success = new WithdrawQuery();
        success.setStatus("SUCCESS");
        assertThat(withdrawService.pageWithdraws(workerId, success).getRecords()).isEmpty();

        assertThat(withdrawNo).isNotBlank();
    }

    /**
     * 提现驳回回退：余额加回、withdrawing 清零，且 REFUND 流水与 WITHDRAW 流水
     * 共享同一 biz_no 靠 type 区分（V12 复合唯一键设计）。
     */
    @Test
    @DisplayName("§9.21 提现驳回回退：余额复原且两条流水共存同一 biz_no")
    void withdrawRejectRestoresBalanceAndKeepsBothTxns() {
        long workerId = seedUser("wd7", "500.00");
        String withdrawNo = withdrawService.apply(workerId, withdrawRequest("200.00", "WECHAT")).getWithdrawNo();

        walletService.withdrawReject(workerId, withdrawNo, new BigDecimal("200.00"));

        WalletVO account = walletService.getAccountVO(workerId);
        assertThat(account.getBalance()).isEqualByComparingTo("500.00");
        assertThat(account.getWithdrawing()).isEqualByComparingTo("0.00");

        List<Map<String, Object>> txns = jdbc.queryForList(
                "SELECT type FROM wallet_transaction WHERE biz_no = ? ORDER BY type", withdrawNo);
        assertThat(txns).extracting(r -> r.get("type")).containsExactly("REFUND", "WITHDRAW");
    }

    /** 提现单号唯一键在真实库生效（幂等最后防线）。 */
    @Test
    @DisplayName("§7.18 uk_withdraw_no 唯一键真实拦截重复单号")
    void duplicateWithdrawNoIsRejectedByUniqueKey() {
        long workerId = seedUser("wd8", "500.00");
        String withdrawNo = withdrawService.apply(workerId, withdrawRequest("100.00", "WECHAT")).getWithdrawNo();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO withdraw_apply (id,withdraw_no,worker_id,amount,fee,actual_amount,channel,status,version) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                System.nanoTime(), withdrawNo, workerId, new BigDecimal("1.00"), BigDecimal.ZERO,
                new BigDecimal("1.00"), "WECHAT", "PENDING", 0))
                .isInstanceOf(DuplicateKeyException.class);
    }

    // ---------------- §7.17 收益概览 ----------------

    @Test
    @DisplayName("§7.17 收益概览：累计/周期/本月口径一致，曲线连续补 0")
    void earningOverviewAggregatesConsistently() {
        long workerId = seedUser("earn1", "0.00");
        // 造两笔 INCOME（模拟两次结算）
        walletService.recharge(workerId, "RCTESTA" + UUID.randomUUID(), new BigDecimal("100.00"));
        jdbc.update("UPDATE wallet_transaction SET type='INCOME' WHERE biz_no LIKE 'RCTESTA%'");
        walletService.recharge(workerId, "RCTESTB" + UUID.randomUUID(), new BigDecimal("150.00"));
        jdbc.update("UPDATE wallet_transaction SET type='INCOME' WHERE biz_no LIKE 'RCTESTB%'");
        jdbc.update("UPDATE wallet_account SET total_income = 250.00 WHERE user_id = ?", workerId);

        EarningVO vo = earningService.getEarning(workerId, "WEEK", 1, 10);

        assertThat(vo.getTotalIncome()).isEqualByComparingTo("250.00");
        assertThat(vo.getPeriodIncome()).isEqualByComparingTo("250.00");
        assertThat(vo.getMonthIncome()).isEqualByComparingTo("250.00");
        assertThat(vo.getOrderCount()).isEqualTo(2L);
        // 单均 = 250 / 2
        assertThat(vo.getAvgIncome()).isEqualByComparingTo("125.00");
        assertThat(vo.getAvailableBalance()).isEqualByComparingTo("250.00");
        // WEEK 曲线固定 7 个点（无收益的补 0，避免前端断点）
        assertThat(vo.getChart()).hasSize(7);
        assertThat(vo.getChart()).allSatisfy(p -> assertThat(p.getAmount()).isNotNull());
        assertThat(vo.getRecentTxns()).isNotEmpty();
    }

    @Test
    @DisplayName("§7.17 无收益时各金额为 0.00 而非 null")
    void earningOverviewDefaultsToZero() {
        long workerId = seedUser("earn2", "0.00");

        EarningVO vo = earningService.getEarning(workerId, "ALL", 1, 10);

        assertThat(vo.getTotalIncome()).isEqualByComparingTo("0.00");
        assertThat(vo.getPeriodIncome()).isEqualByComparingTo("0.00");
        assertThat(vo.getAvgIncome()).isEqualByComparingTo("0.00");
        assertThat(vo.getOrderCount()).isZero();
        assertThat(vo.getChart()).hasSize(7);
    }

    // ---------------- 审计表（阶段 4 建表，阶段 7 才做界面） ----------------

    @Test
    @DisplayName("admin_operation_log 表可按目标追溯查询（阶段7 前置）")
    void adminOperationLogIsQueryable() {
        String targetId = "WD" + UUID.randomUUID();
        jdbc.update("INSERT INTO admin_operation_log (id,admin_id,admin_name,action_type,target_type,"
                        + "target_id,before_snapshot,after_snapshot,remark) VALUES (?,?,?,?,?,?,?,?,?)",
                System.nanoTime(), 1L, "测试管理员", "WITHDRAW_APPROVE", "WITHDRAW",
                targetId, "{\"status\":\"PENDING\"}", "{\"status\":\"SUCCESS\"}", "模拟打款");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT action_type, before_snapshot, after_snapshot FROM admin_operation_log "
                        + "WHERE target_type = 'WITHDRAW' AND target_id = ?", targetId);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("action_type")).isEqualTo("WITHDRAW_APPROVE");
        assertThat(rows.get(0).get("before_snapshot")).asString().contains("PENDING");
        assertThat(rows.get(0).get("after_snapshot")).asString().contains("SUCCESS");
    }

    // ---------------- 辅助 ----------------

    /**
     * 生成符合真实格式的短订单号（&lt;= 32 字符，适配 order_no VARCHAR(32)）。
     * 形如 MP + 14 位时间 + 6 位随机，与 OrderNoGenerator 的产物同量级。
     */
    private String shortOrderNo(String prefix) {
        String ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int rand = Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextInt()) % 1_000_000;
        return prefix + ts + String.format("%06d", rand);
    }

    private long seedUser(String suffix, String balance) {
        String openid = "phase4_" + suffix + "_" + UUID.randomUUID().toString().substring(0, 8);
        long userId = 8_000_000_000L + Math.abs(openid.hashCode() % 100_000_000L);
        jdbc.update("INSERT INTO sys_user (id, openid, nickname, role_mask, status) VALUES (?,?,?,?,?)",
                userId, openid, "阶段4测试用户", 3, 1);
        jdbc.update("INSERT INTO wallet_account (id, user_id, balance, frozen, withdrawing, "
                        + "total_income, total_recharge, total_expense, version) VALUES (?,?,?,?,?,?,?,?,?)",
                userId + 1, userId, new BigDecimal(balance), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        return userId;
    }

    private RechargeRequest rechargeRequest(String amount) {
        RechargeRequest request = new RechargeRequest();
        request.setAmount(new BigDecimal(amount));
        return request;
    }

    private RechargeCallbackRequest callback(String rechargeNo, String result) {
        RechargeCallbackRequest request = new RechargeCallbackRequest();
        request.setRechargeNo(rechargeNo);
        request.setResult(result);
        return request;
    }

    private WithdrawApplyRequest withdrawRequest(String amount, String channel) {
        WithdrawApplyRequest request = new WithdrawApplyRequest();
        request.setAmount(new BigDecimal(amount));
        request.setChannel(channel);
        return request;
    }
}
