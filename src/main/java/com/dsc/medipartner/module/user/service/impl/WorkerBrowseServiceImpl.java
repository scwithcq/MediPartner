package com.dsc.medipartner.module.user.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.result.SortWhitelist;
import com.dsc.medipartner.common.util.GeoUtil;
import com.dsc.medipartner.module.review.service.ReviewService;
import com.dsc.medipartner.module.user.domain.dto.WorkerQuery;
import com.dsc.medipartner.module.user.domain.enums.AuditStatusEnum;
import com.dsc.medipartner.module.user.domain.enums.CreditLevel;
import com.dsc.medipartner.module.user.domain.row.WorkerBrowseRow;
import com.dsc.medipartner.module.user.domain.vo.WorkerCardVO;
import com.dsc.medipartner.module.user.domain.vo.WorkerDetailVO;
import com.dsc.medipartner.module.user.mapper.WorkerProfileMapper;
import com.dsc.medipartner.module.user.service.WorkerBrowseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 陪诊师浏览实现（契约 §6.1 / §6.2）。
 * 列表投影已在 SQL 过滤 audit_status=PASS、sys_user.status=1、worker_profile.status=1；
 * 详情不过滤，由本类按 2001（不存在）/ 2003（未过审或停用）拦截。
 * 星级分布与最近评价委托 ReviewService，冗余统计列（good_rate/avg_star）仅作列表快照。
 */
@Slf4j
@Service
public class WorkerBrowseServiceImpl implements WorkerBrowseService {

    private static final int DEFAULT_CREDIT = 100;
    private static final int DEFAULT_GOOD_RATE = 100;
    /** 列表页 intro 截断长度（契约 §6.1），详情页不截断 */
    private static final int INTRO_LIST_MAX = 60;
    /** 信用分不低于此值才算「可接单」（契约 §6.1 accepting） */
    private static final int MIN_ACCEPT_CREDIT = 60;
    private static final BigDecimal TOP_RATED_STAR = new BigDecimal("4.8");
    private static final int TOP_RATED_MIN_REVIEWS = 5;
    private static final int RECENT_REVIEW_LIMIT = 3;
    private static final int DEFAULT_RESPONSE_RATE = 100;

    private final WorkerProfileMapper workerProfileMapper;
    private final ReviewService reviewService;

    public WorkerBrowseServiceImpl(WorkerProfileMapper workerProfileMapper, ReviewService reviewService) {
        this.workerProfileMapper = workerProfileMapper;
        this.reviewService = reviewService;
    }

    @Override
    public IPage<WorkerCardVO> pageWorkers(WorkerQuery query) {
        BigDecimal callerLat = GeoUtil.parseCoordinate(query.getGpsLat());
        BigDecimal callerLng = GeoUtil.parseCoordinate(query.getGpsLng());
        boolean hasCallerGeo = GeoUtil.isValid(callerLat, callerLng);
        boolean sortByDistance = hasCallerGeo && "distance".equals(query.sortProperty());

        if (!sortByDistance) {
            Page<WorkerBrowseRow> page = new Page<>(query.getPageNum(), query.getPageSize());
            IPage<WorkerBrowseRow> rows = workerProfileMapper.browse(page, query);
            Page<WorkerCardVO> result = new Page<>(rows.getCurrent(), rows.getSize(), rows.getTotal());
            result.setRecords(rows.getRecords().stream().map(row -> toCard(row, callerLat, callerLng)).toList());
            return result;
        }

        // 距离排序：SQL 无法表达（库里只有坐标，没有距离列），改为取出候选后内存精算。
        // 上限兜底同大厅：避免陪诊师数量增长后一次拉全表。
        Page<WorkerBrowseRow> candidatePage = new Page<>(1, DISTANCE_SORT_MAX_CANDIDATES);
        IPage<WorkerBrowseRow> rows = workerProfileMapper.browse(candidatePage, query);
        List<WorkerBrowseRow> candidates = rows.getRecords();
        if (candidates.size() >= DISTANCE_SORT_MAX_CANDIDATES) {
            log.warn("[worker] 距离排序候选达到上限 {}，建议改用 GeoHash 分桶查询",
                    DISTANCE_SORT_MAX_CANDIDATES);
        }

        // 尊重 §1.8 的 order 方向（只认 sortBy 而忽略 order 会让 desc 静默失效）
        boolean ascending = SortWhitelist.Spec.WORKER_LIST
                .resolve(query.getSortBy(), query.getOrder()).asc();
        Comparator<WorkerBrowseRow> byDistance = Comparator.comparing(
                (WorkerBrowseRow r) -> distanceTo(r, callerLat, callerLng),
                Comparator.nullsLast(Comparator.naturalOrder()));
        List<WorkerBrowseRow> sorted = candidates.stream()
                .sorted((ascending ? byDistance : byDistance.reversed())
                        // 同距离按好评率降序，再按 userId，保证顺序稳定可复现。
                        // 这一层不随 order 反转，理由同大厅：它是确定性兜底而非用户可见语义。
                        .thenComparing(WorkerBrowseRow::getGoodRate,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(WorkerBrowseRow::getUserId))
                .toList();

        long current = query.getPageNum();
        long size = query.getPageSize();
        int from = (int) Math.min((current - 1) * size, sorted.size());
        int to = (int) Math.min(from + size, sorted.size());

        Page<WorkerCardVO> result = new Page<>(current, size, sorted.size());
        result.setRecords(sorted.subList(from, to).stream()
                .map(row -> toCard(row, callerLat, callerLng)).toList());
        return result;
    }

    /** 距离排序的候选上限，防止陪诊师规模增长后一次拉全表 */
    private static final int DISTANCE_SORT_MAX_CANDIDATES = 2000;

    /**
     * 陪诊师到查询者的距离（km）。
     *
     * <p>陪诊师未上报过定位时返回 {@code null}，排序时排到最后——
     * 而不是当成 0（0 会被误读为「就在身边」，把没定位的人排到最前面）。
     */
    private BigDecimal distanceTo(WorkerBrowseRow row, BigDecimal callerLat, BigDecimal callerLng) {
        return GeoUtil.distanceKm(callerLat, callerLng, row.getGpsLat(), row.getGpsLng());
    }

    @Override
    public WorkerDetailVO getDetail(Long workerUserId) {
        WorkerBrowseRow row = workerProfileMapper.browseDetail(workerUserId);
        if (row == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        boolean approved = AuditStatusEnum.PASS.name().equals(row.getAuditStatus());
        boolean active = row.getStatus() != null && row.getStatus() == 1;
        if (!approved || !active) {
            throw new BizException(ErrorCode.WORKER_AUDIT_NOT_PASS);
        }

        WorkerDetailVO vo = new WorkerDetailVO();
        fillCard(vo, row, false);
        vo.setAuditStatus(row.getAuditStatus());
        vo.setCertVerified(StringUtils.hasText(row.getHealthCertUrl()));
        vo.setIdCardVerified(Boolean.TRUE.equals(row.getIdCardVerified()));
        vo.setStarDistribution(reviewService.starDistribution(workerUserId));
        vo.setAvgStar(WorkerServiceImpl.oneDecimal(row.getAvgStar()));
        vo.setRecentReviews(reviewService.recentPublicReviews(workerUserId, RECENT_REVIEW_LIMIT));
        vo.setResponseRate(DEFAULT_RESPONSE_RATE);
        vo.setJoinedAt(row.getAuditTime());
        vo.setServiceCities(StringUtils.hasText(row.getCity()) ? List.of(row.getCity()) : List.of());
        vo.setBadges(badges(row));
        vo.setUpdatedAt(row.getUpdatedAt());
        return vo;
    }

    private WorkerCardVO toCard(WorkerBrowseRow row, BigDecimal callerLat, BigDecimal callerLng) {
        WorkerCardVO vo = new WorkerCardVO();
        fillCard(vo, row, true);
        // 阶段 5（§6.1）：仅当查询方带了坐标才下发 distanceKm，否则键缺失
        if (GeoUtil.isValid(callerLat, callerLng)) {
            vo.setDistanceKm(distanceTo(row, callerLat, callerLng));
        }
        return vo;
    }

    /** 卡片公共字段；truncateIntro=true 时 intro 截断至 60 字（列表），详情传 false 保留全文 */
    private void fillCard(WorkerCardVO vo, WorkerBrowseRow row, boolean truncateIntro) {
        int score = row.getCreditScore() == null ? DEFAULT_CREDIT : row.getCreditScore();
        vo.setWorkerUserId(String.valueOf(row.getUserId()));
        vo.setNickname(row.getNickname());
        vo.setAvatar(row.getAvatar());
        vo.setRealName(maskRealName(row.getRealName()));
        vo.setCity(row.getCity());
        vo.setCreditScore(score);
        vo.setCreditLevel(CreditLevel.of(score).name());
        vo.setGoodRate(row.getGoodRate() == null ? DEFAULT_GOOD_RATE : row.getGoodRate());
        vo.setReviewCount(row.getReviewCount() == null ? 0 : row.getReviewCount());
        vo.setServiceCount(row.getServiceCount() == null ? 0 : row.getServiceCount());
        List<String> types = WorkerServiceImpl.splitCsv(row.getServiceTypes());
        vo.setServiceTypes(types);
        vo.setServiceTypeNames(WorkerServiceImpl.serviceTypeNames(types));
        vo.setIntro(truncateIntro ? truncate(row.getIntro(), INTRO_LIST_MAX) : row.getIntro());
        vo.setTags(tags(row));
        vo.setAccepting(score >= MIN_ACCEPT_CREDIT);
    }

    /** 脱敏真实姓名：只显示姓，名以「某某」代替（契约 §6.1） */
    private String maskRealName(String realName) {
        if (!StringUtils.hasText(realName)) {
            return null;
        }
        return realName.trim().charAt(0) + "某某";
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /** 平台标签：实名认证（id_card_no 非空）、健康证已验（health_cert_url 非空） */
    private List<String> tags(WorkerBrowseRow row) {
        List<String> tags = new ArrayList<>(2);
        if (Boolean.TRUE.equals(row.getIdCardVerified())) {
            tags.add("实名认证");
        }
        if (StringUtils.hasText(row.getHealthCertUrl())) {
            tags.add("健康证已验");
        }
        return tags;
    }

    /** 徽章码值：REAL_NAME / HEALTH_CERT / TOP_RATED（avgStar>=4.8 且评价数>=5） */
    private List<String> badges(WorkerBrowseRow row) {
        List<String> badges = new ArrayList<>(3);
        if (Boolean.TRUE.equals(row.getIdCardVerified())) {
            badges.add("REAL_NAME");
        }
        if (StringUtils.hasText(row.getHealthCertUrl())) {
            badges.add("HEALTH_CERT");
        }
        int reviewCount = row.getReviewCount() == null ? 0 : row.getReviewCount();
        BigDecimal avgStar = row.getAvgStar();
        if (avgStar != null && avgStar.compareTo(TOP_RATED_STAR) >= 0 && reviewCount >= TOP_RATED_MIN_REVIEWS) {
            badges.add("TOP_RATED");
        }
        return badges;
    }
}