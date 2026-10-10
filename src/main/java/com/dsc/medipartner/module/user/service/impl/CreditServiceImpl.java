package com.dsc.medipartner.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.user.domain.entity.CreditChangeLog;
import com.dsc.medipartner.module.user.domain.entity.WorkerProfile;
import com.dsc.medipartner.module.user.domain.enums.CreditChangeType;
import com.dsc.medipartner.module.user.domain.enums.CreditLevel;
import com.dsc.medipartner.module.user.domain.vo.CreditVO;
import com.dsc.medipartner.module.user.mapper.CreditChangeLogMapper;
import com.dsc.medipartner.module.user.mapper.WorkerProfileMapper;
import com.dsc.medipartner.module.user.service.CreditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 信用中心实现（契约 §7.16）：变动全部由后端事件驱动（完成订单/好评/差评/爽约等），
 * 分数钳制 0~100，每次变动写 credit_change_log；trend 按天取末值、无变动沿用前值保证曲线连续。
 */
@Slf4j
@Service
public class CreditServiceImpl implements CreditService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int TREND_DAYS = 30;
    private static final int RECORD_LIMIT = 20;
    private static final int DEFAULT_SCORE = 100;

    private final WorkerProfileMapper workerProfileMapper;
    private final CreditChangeLogMapper creditChangeLogMapper;

    @Value("${medi.order.max-active-orders:3}")
    private int maxActiveOrders;
    @Value("${medi.order.max-active-orders-low-credit:1}")
    private int maxActiveOrdersLowCredit;
    @Value("${medi.order.min-credit-accept:60}")
    private int minCreditAccept;
    @Value("${medi.order.low-credit-threshold:80}")
    private int lowCreditThreshold;

    public CreditServiceImpl(WorkerProfileMapper workerProfileMapper, CreditChangeLogMapper creditChangeLogMapper) {
        this.workerProfileMapper = workerProfileMapper;
        this.creditChangeLogMapper = creditChangeLogMapper;
    }

    @Override
    @Transactional
    public void change(Long workerUserId, CreditChangeType type, String orderNo, Long reviewId) {
        WorkerProfile profile = workerProfileMapper.selectOne(new LambdaQueryWrapper<WorkerProfile>()
                .eq(WorkerProfile::getUserId, workerUserId));
        if (profile == null) {
            log.warn("[credit] 陪诊师档案不存在，跳过信用分变动 workerUserId={} type={}", workerUserId, type);
            return;
        }
        int before = profile.getCreditScore() == null ? DEFAULT_SCORE : profile.getCreditScore();
        int after = clamp(before + type.getScore());
        workerProfileMapper.update(null, new LambdaUpdateWrapper<WorkerProfile>()
                .eq(WorkerProfile::getUserId, workerUserId)
                .set(WorkerProfile::getCreditScore, after));

        // 事项 5B：change_score 记【实际差值】而非枚举名义分值。
        // 信用分钳制在 0~100，满级(100)时 ORDER_COMPLETED(+2) 实际加 0 分，
        // 若仍记 +2 会让信用明细出现「加 2 分但总分没变」的误导；
        // 但仍保留该行日志，因为「这一单触发过加分事件」本身是审计需要的信息。
        int actualDelta = after - before;

        CreditChangeLog changeLog = new CreditChangeLog();
        changeLog.setWorkerUserId(workerUserId);
        changeLog.setChangeType(type.name());
        changeLog.setChangeScore(actualDelta);
        changeLog.setBeforeScore(before);
        changeLog.setAfterScore(after);
        changeLog.setOrderNo(orderNo);
        changeLog.setReviewId(reviewId);
        changeLog.setReason(type.getReason());
        creditChangeLogMapper.insert(changeLog);
        if (actualDelta == 0) {
            log.info("[credit] 信用分已达边界，实际未变动 workerUserId={} type={} 名义分值={} 当前={}",
                    workerUserId, type, type.getScore(), before);
        } else {
            log.info("[credit] 信用分变动 workerUserId={} type={} {} -> {}（实际 {})",
                    workerUserId, type, before, after, actualDelta > 0 ? "+" + actualDelta : actualDelta);
        }
    }

    @Override
    public CreditVO getCreditCenter(Long workerUserId) {
        WorkerProfile profile = workerProfileMapper.selectOne(new LambdaQueryWrapper<WorkerProfile>()
                .eq(WorkerProfile::getUserId, workerUserId));
        if (profile == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        int score = profile.getCreditScore() == null ? DEFAULT_SCORE : profile.getCreditScore();
        CreditLevel level = CreditLevel.of(score);

        CreditVO vo = new CreditVO();
        vo.setCreditScore(score);
        vo.setCreditLevel(level.name());
        vo.setLevelDesc(score < minCreditAccept
                ? CreditLevel.POOR.getLevelDesc()
                : (score < lowCreditThreshold ? CreditLevel.NORMAL.getLevelDesc() : level.getLevelDesc()));
        vo.setActiveLimit(score < lowCreditThreshold ? maxActiveOrdersLowCredit : maxActiveOrders);
        vo.setNextLevelScore(level.nextLevelScore(score));
        vo.setTrend(buildTrend(workerUserId, score));
        vo.setRecords(buildRecords(workerUserId));
        vo.setRules(buildRules());
        vo.setCanAccept(score >= minCreditAccept);
        return vo;
    }

    @Override
    public Integer getImpactScore(Long reviewId) {
        if (reviewId == null) {
            return null;
        }
        CreditChangeLog changeLog = creditChangeLogMapper.selectOne(new LambdaQueryWrapper<CreditChangeLog>()
                .eq(CreditChangeLog::getReviewId, reviewId)
                .orderByDesc(CreditChangeLog::getId)
                .last("LIMIT 1"));
        return changeLog == null ? null : changeLog.getChangeScore();
    }

    /** 近 30 天曲线：按天取末值，无变动的那天沿用前值；窗口内无流水则基线为当前分 */
    private List<CreditVO.TrendPoint> buildTrend(Long workerUserId, int currentScore) {
        LocalDateTime since = LocalDate.now().minusDays(TREND_DAYS - 1L).atStartOfDay();
        List<CreditChangeLog> logs = creditChangeLogMapper.selectList(new LambdaQueryWrapper<CreditChangeLog>()
                .eq(CreditChangeLog::getWorkerUserId, workerUserId)
                .ge(CreditChangeLog::getCreatedAt, since)
                .orderByAsc(CreditChangeLog::getCreatedAt));
        Map<LocalDate, Integer> lastScoreByDay = new HashMap<>();
        for (CreditChangeLog changeLog : logs) {
            if (changeLog.getCreatedAt() != null) {
                lastScoreByDay.put(changeLog.getCreatedAt().toLocalDate(), changeLog.getAfterScore());
            }
        }
        int prev = logs.isEmpty() ? currentScore
                : (logs.get(0).getBeforeScore() == null ? currentScore : logs.get(0).getBeforeScore());
        List<CreditVO.TrendPoint> trend = new ArrayList<>(TREND_DAYS);
        LocalDate start = LocalDate.now().minusDays(TREND_DAYS - 1L);
        for (int i = 0; i < TREND_DAYS; i++) {
            LocalDate day = start.plusDays(i);
            Integer dayScore = lastScoreByDay.get(day);
            if (dayScore != null) {
                prev = dayScore;
            }
            trend.add(new CreditVO.TrendPoint(day.format(DAY), prev));
        }
        return trend;
    }

    private List<CreditVO.RecordItem> buildRecords(Long workerUserId) {
        List<CreditChangeLog> logs = creditChangeLogMapper.selectList(new LambdaQueryWrapper<CreditChangeLog>()
                .eq(CreditChangeLog::getWorkerUserId, workerUserId)
                .orderByDesc(CreditChangeLog::getCreatedAt)
                .orderByDesc(CreditChangeLog::getId)
                .last("LIMIT " + RECORD_LIMIT));
        List<CreditVO.RecordItem> records = new ArrayList<>(logs.size());
        for (CreditChangeLog changeLog : logs) {
            records.add(new CreditVO.RecordItem(signed(changeLog.getChangeScore()), changeLog.getReason(),
                    changeLog.getOrderNo(), changeLog.getCreatedAt()));
        }
        return records;
    }

    private List<CreditVO.RuleItem> buildRules() {
        return Arrays.stream(CreditChangeType.values())
                .filter(type -> type.getScore() != 0)
                .map(type -> new CreditVO.RuleItem(type.getReason(),
                        "信用分 " + signed(type.getScore()) + " 分"))
                .toList();
    }

    private String signed(Integer score) {
        int value = score == null ? 0 : score;
        return (value > 0 ? "+" : "") + value;
    }

    private int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }
}
