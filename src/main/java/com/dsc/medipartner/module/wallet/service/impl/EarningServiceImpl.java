package com.dsc.medipartner.module.wallet.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.module.wallet.domain.dto.TransactionQuery;
import com.dsc.medipartner.module.wallet.domain.entity.WalletTransaction;
import com.dsc.medipartner.module.wallet.domain.enums.TxnTypeEnum;
import com.dsc.medipartner.module.wallet.domain.vo.EarningVO;
import com.dsc.medipartner.module.wallet.domain.vo.TransactionVO;
import com.dsc.medipartner.module.wallet.domain.vo.WalletVO;
import com.dsc.medipartner.module.wallet.mapper.WalletTransactionMapper;
import com.dsc.medipartner.module.wallet.service.EarningService;
import com.dsc.medipartner.module.wallet.service.WalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 收益概览实现（契约 §7.17）。
 *
 * <p>统计口径：一律基于 {@code wallet_transaction} 的 {@code INCOME} 流水（结算入账的真实记录），
 * <b>不反查订单表</b>——既避免 wallet→order 的环依赖，也保证「收益」与「钱包流水」两个口径永远一致。
 * 单数由同批 INCOME 流水的条数得出（本平台不抽成，一单结算恰好一条 INCOME）。
 */
@Slf4j
@Service
public class EarningServiceImpl implements EarningService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int CHART_DAYS_WEEK = 7;
    private static final int CHART_MONTHS_YEAR = 12;

    private final WalletTransactionMapper txnMapper;
    private final WalletService walletService;

    public EarningServiceImpl(WalletTransactionMapper txnMapper, WalletService walletService) {
        this.txnMapper = txnMapper;
        this.walletService = walletService;
    }

    @Override
    public EarningVO getEarning(Long workerId, String period, long txnPageNum, long txnPageSize) {
        PeriodRange range = resolveRange(period);
        WalletVO account = walletService.getAccountVO(workerId);

        EarningVO vo = new EarningVO();
        // 累计收益直接取钱包累计列（V14 起由 settle 维护，只增不减）
        vo.setTotalIncome(nz(account.getTotalIncome()));

        List<WalletTransaction> allIncome = txnMapper.selectList(new LambdaQueryWrapper<WalletTransaction>()
                .eq(WalletTransaction::getUserId, workerId)
                .eq(WalletTransaction::getType, TxnTypeEnum.INCOME.name())
                .orderByAsc(WalletTransaction::getCreatedAt)
                .orderByAsc(WalletTransaction::getId));

        BigDecimal periodIncome = BigDecimal.ZERO;
        long periodCount = 0L;
        // 曲线按桶累计：WEEK/MONTH 按天，YEAR 按月
        Map<String, BigDecimal> bucketAmount = new LinkedHashMap<>();
        Map<String, Long> bucketCount = new LinkedHashMap<>();
        String bucketFormat = range.monthly ? "yyyy-MM" : "yyyy-MM-dd";

        for (WalletTransaction txn : allIncome) {
            LocalDateTime at = txn.getCreatedAt();
            if (at == null) {
                continue;
            }
            if (range.start != null && at.isBefore(range.start)) {
                continue;
            }
            periodIncome = periodIncome.add(nz(txn.getAmount()));
            periodCount++;
            String key = at.format(DateTimeFormatter.ofPattern(bucketFormat));
            bucketAmount.merge(key, nz(txn.getAmount()), BigDecimal::add);
            bucketCount.merge(key, 1L, Long::sum);
        }

        vo.setPeriodIncome(periodIncome);
        vo.setMonthIncome(sumMonthIncome(allIncome, LocalDate.now()));
        vo.setOrderCount(periodCount);
        // 单均收益 = 周期收益 / 周期单数；无单时为 0.00
        vo.setAvgIncome(periodCount == 0
                ? BigDecimal.ZERO.setScale(2)
                : periodIncome.divide(BigDecimal.valueOf(periodCount), 2, RoundingMode.HALF_UP));

        vo.setAvailableBalance(nz(account.getBalance()));
        vo.setFrozenAmount(nz(account.getFrozen()));
        vo.setWithdrawing(nz(account.getWithdrawing()));

        vo.setChart(buildChart(range, bucketAmount, bucketCount));
        vo.setRecentTxns(loadRecentTxns(workerId, txnPageNum, txnPageSize));
        return vo;
    }

    @Override
    public BigDecimal getMonthIncome(Long workerId) {
        LocalDate month = LocalDate.now().withDayOfMonth(1);
        List<WalletTransaction> income = txnMapper.selectList(new LambdaQueryWrapper<WalletTransaction>()
                .eq(WalletTransaction::getUserId, workerId)
                .eq(WalletTransaction::getType, TxnTypeEnum.INCOME.name())
                .ge(WalletTransaction::getCreatedAt, month.atStartOfDay())
                .lt(WalletTransaction::getCreatedAt, month.plusMonths(1).atStartOfDay()));
        return sumMonthIncome(income, month);
    }

    private BigDecimal sumMonthIncome(List<WalletTransaction> income, LocalDate today) {
        LocalDateTime start = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime end = start.plusMonths(1);
        BigDecimal total = BigDecimal.ZERO;
        for (WalletTransaction txn : income) {
            LocalDateTime at = txn.getCreatedAt();
            if (at != null && !at.isBefore(start) && at.isBefore(end)) {
                total = total.add(nz(txn.getAmount()));
            }
        }
        return total;
    }

    /**
     * 生成连续曲线：无收益的时段补 0，保证前端画图不出现断点。
     * 与 §7.16 的 trend「无变动沿用前值」不同——收益曲线补 0 更符合「该日没有进账」的事实。
     */
    private List<EarningVO.ChartPoint> buildChart(PeriodRange range,
                                                  Map<String, BigDecimal> amount,
                                                  Map<String, Long> count) {
        List<EarningVO.ChartPoint> points = new ArrayList<>();
        LocalDate today = LocalDate.now();
        if (range.monthly) {
            LocalDate month = today.withDayOfMonth(1).minusMonths(CHART_MONTHS_YEAR - 1L);
            for (int i = 0; i < CHART_MONTHS_YEAR; i++) {
                LocalDate current = month.plusMonths(i);
                String key = current.format(DateTimeFormatter.ofPattern("yyyy-MM"));
                points.add(point(current, amount.get(key), count.get(key)));
            }
        } else {
            LocalDate day = today.minusDays(CHART_DAYS_WEEK - 1L);
            for (int i = 0; i < CHART_DAYS_WEEK; i++) {
                LocalDate current = day.plusDays(i);
                String key = current.format(DAY);
                points.add(point(current, amount.get(key), count.get(key)));
            }
        }
        return points;
    }

    private EarningVO.ChartPoint point(LocalDate date, BigDecimal amt, Long cnt) {
        EarningVO.ChartPoint p = new EarningVO.ChartPoint();
        p.setDate(date);
        p.setPeriodStart(date.atStartOfDay());
        p.setAmount(amt == null ? BigDecimal.ZERO.setScale(2) : amt);
        p.setCount(cnt == null ? 0L : cnt);
        return p;
    }

    /** 最近资金流水，元素结构同 §3.7（复用 transactionVO 的字段口径） */
    private List<TransactionVO> loadRecentTxns(Long workerId, long pageNum, long pageSize) {
        TransactionQuery query = new TransactionQuery();
        long effectiveSize = Math.min(Math.max(pageSize, 1), 50);
        query.setPageNum(Math.max(pageNum, 1));
        query.setPageSize(effectiveSize);
        IPage<TransactionVO> page = walletService.pageTransactionVOs(workerId, query);
        return page.getRecords();
    }

    /** 周期范围；{@code ALL} 不设起点 */
    private PeriodRange resolveRange(String period) {
        String normalized = StringUtils.hasText(period) ? period.trim().toUpperCase(Locale.ROOT) : "WEEK";
        LocalDate today = LocalDate.now();
        return switch (normalized) {
            case "MONTH" -> new PeriodRange(today.withDayOfMonth(1).atStartOfDay(), false);
            case "YEAR" -> new PeriodRange(today.withDayOfYear(1).atStartOfDay(), true);
            case "ALL" -> new PeriodRange(null, false);
            // 默认 WEEK
            default -> new PeriodRange(today.minusDays(CHART_DAYS_WEEK - 1L).atStartOfDay(), false);
        };
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value;
    }

    /** @param start 统计起点，null 表示不限；@param monthly 曲线是否按月聚合 */
    private record PeriodRange(LocalDateTime start, boolean monthly) {
    }
}
