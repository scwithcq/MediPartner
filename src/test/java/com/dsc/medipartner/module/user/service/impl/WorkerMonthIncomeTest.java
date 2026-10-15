package com.dsc.medipartner.module.user.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsc.medipartner.module.order.service.OrderService;
import com.dsc.medipartner.module.user.domain.entity.SysUser;
import com.dsc.medipartner.module.user.domain.entity.WorkerProfile;
import com.dsc.medipartner.module.user.mapper.WorkerProfileMapper;
import com.dsc.medipartner.module.user.service.UserService;
import com.dsc.medipartner.module.wallet.domain.entity.WalletTransaction;
import com.dsc.medipartner.module.wallet.domain.vo.WalletVO;
import com.dsc.medipartner.module.wallet.mapper.WalletTransactionMapper;
import com.dsc.medipartner.module.wallet.service.WalletService;
import com.dsc.medipartner.module.wallet.service.impl.EarningServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkerMonthIncomeTest {
    private static final long WORKER = 10003L;
    private final List<WalletTransaction> transactions = new ArrayList<>();
    private WorkerServiceImpl workerService;
    private EarningServiceImpl earningService;
    private WorkerProfile profile;

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WorkerProfile.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WalletTransaction.class);
        WorkerProfileMapper profiles = mock(WorkerProfileMapper.class);
        UserService users = mock(UserService.class);
        OrderService orders = mock(OrderService.class);
        WalletService wallet = mock(WalletService.class);
        WalletTransactionMapper txns = mock(WalletTransactionMapper.class);
        profile = new WorkerProfile();
        profile.setAuditStatus("PASS");
        when(profiles.selectOne(any())).thenReturn(profile);
        when(users.getById(WORKER)).thenReturn(new SysUser());
        when(orders.pageWorkerOrderCards(any(), any())).thenReturn(new Page<>());
        when(wallet.pageTransactionVOs(any(), any())).thenReturn(new Page<>());
        WalletVO account = new WalletVO();
        account.setTotalIncome(new BigDecimal("9999.00"));
        account.setWithdrawing(new BigDecimal("20.00"));
        when(wallet.getAccountVO(WORKER)).thenReturn(account);
        when(txns.selectList(any())).thenAnswer(invocation -> new ArrayList<>(transactions));
        earningService = new EarningServiceImpl(txns, wallet);
        workerService = new WorkerServiceImpl(profiles, users, orders, wallet, earningService);
    }

    @Test
    void overviewMatchesEarningPageAndExcludesOtherMonths() {
        LocalDateTime start = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        transactions.add(income(start.minusSeconds(1), "1000.00"));
        transactions.add(income(start, "215.00"));
        transactions.add(income(start.plusDays(1), "400.00"));
        transactions.add(income(start.plusMonths(1), "5000.00"));
        transactions.add(income(null, "2000.00"));
        assertThat(workerService.getOverview(WORKER).getMonthEarning()).isEqualByComparingTo("615.00");
        assertThat(workerService.getOverview(WORKER).getMonthEarning())
                .isEqualByComparingTo(earningService.getEarning(WORKER, "WEEK", 1, 10).getMonthIncome());
        assertThat(workerService.getOverview(WORKER).getPendingWithdraw()).isEqualByComparingTo("20.00");
    }

    @Test
    void freshOverviewReflectsNewSettlement() {
        assertThat(workerService.getOverview(WORKER).getMonthEarning()).isZero();
        transactions.add(income(LocalDateTime.now(), "220.00"));
        assertThat(workerService.getOverview(WORKER).getMonthEarning()).isEqualByComparingTo("220.00");
    }

    @Test
    void unapprovedWorkerRetainsZeroStatistics() {
        profile.setAuditStatus("PENDING");
        transactions.add(income(LocalDateTime.now(), "220.00"));
        assertThat(workerService.getOverview(WORKER).getMonthEarning()).isZero();
    }

    private WalletTransaction income(LocalDateTime at, String amount) {
        WalletTransaction txn = new WalletTransaction();
        txn.setType("INCOME");
        txn.setUserId(WORKER);
        txn.setCreatedAt(at);
        txn.setAmount(new BigDecimal(amount));
        return txn;
    }
}
