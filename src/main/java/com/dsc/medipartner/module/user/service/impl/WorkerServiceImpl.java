package com.dsc.medipartner.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.util.GeoUtil;
import com.dsc.medipartner.common.util.SensitiveWords;
import com.dsc.medipartner.module.order.domain.dto.OrderQuery;
import com.dsc.medipartner.module.order.domain.enums.ServiceType;
import com.dsc.medipartner.module.order.domain.vo.OrderCardVO;
import com.dsc.medipartner.module.order.service.OrderService;
import com.dsc.medipartner.module.user.domain.dto.UpdateWorkerProfileRequest;
import com.dsc.medipartner.module.user.domain.dto.WorkerApplyRequest;
import com.dsc.medipartner.module.user.domain.entity.SysUser;
import com.dsc.medipartner.module.user.domain.entity.WorkerProfile;
import com.dsc.medipartner.module.user.domain.dto.ReportGeoRequest;
import com.dsc.medipartner.module.user.domain.enums.AuditStatusEnum;
import com.dsc.medipartner.module.user.domain.enums.CreditLevel;
import com.dsc.medipartner.module.user.domain.vo.GeoReportVO;
import com.dsc.medipartner.module.user.domain.vo.WorkerApplyStatusVO;
import com.dsc.medipartner.module.user.domain.vo.WorkerOverviewVO;
import com.dsc.medipartner.module.user.domain.vo.WorkerProfileVO;
import com.dsc.medipartner.module.user.mapper.WorkerProfileMapper;
import com.dsc.medipartner.module.user.service.UserService;
import com.dsc.medipartner.module.user.service.WorkerService;
import com.dsc.medipartner.module.wallet.domain.vo.WalletVO;
import com.dsc.medipartner.module.wallet.service.WalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class WorkerServiceImpl implements WorkerService {

    /** 无评价时平均星级兜底（契约 §6.2） */
    private static final BigDecimal DEFAULT_AVG_STAR = new BigDecimal("5.0");
    private static final int DEFAULT_CREDIT = 100;

    /**
     * GeoHash 精度（契约 §7.4 备注 1）：6 位约 1.2km。
     *
     * <p>再长会让粗筛过严（邻格订单被滤掉，需要额外查 8 个邻格）；
     * 再短则筛得太粗（1 位约 5000km），失去减少候选量的意义。
     */
    private static final int GEO_HASH_PRECISION = 6;

    @Value("${medi.order.max-active-orders:3}")
    private int maxActiveOrders;
    @Value("${medi.order.max-active-orders-low-credit:1}")
    private int maxActiveOrdersLowCredit;
    @Value("${medi.order.min-credit-accept:60}")
    private int minCreditAccept;
    @Value("${medi.order.low-credit-threshold:80}")
    private int lowCreditThreshold;

    private final WorkerProfileMapper workerProfileMapper;
    private final UserService userService;
    private final OrderService orderService;
    private final WalletService walletService;

    public WorkerServiceImpl(WorkerProfileMapper workerProfileMapper,
                             @Lazy UserService userService,
                             @Lazy OrderService orderService,
                             @Lazy WalletService walletService) {
        this.workerProfileMapper = workerProfileMapper;
        this.userService = userService;
        this.orderService = orderService;
        this.walletService = walletService;
    }

    @Override
    public WorkerProfile getByUserId(Long userId) {
        return workerProfileMapper.selectOne(new LambdaQueryWrapper<WorkerProfile>()
                .eq(WorkerProfile::getUserId, userId));
    }

    // ---------------- §7.4 定位上报（阶段 5） ----------------

    /**
     * 上报定位并回写 GeoHash。
     *
     * <p><b>为什么必须同时存原始经纬度</b>：GeoHash 只能做前缀粗筛（前 6 位约 1.2km），
     * 无法还原精确距离。契约 §7.4 备注 1 要求「前缀粗筛 + 内存精算距离」，
     * 所以 {@code gps_lat}/{@code gps_lng} 才是距离计算的真正数据来源，
     * {@code geo_hash} 只是给粗筛用的索引前缀。
     *
     * <p>{@code nearbyOrderCount} 委托 {@code OrderService} 计算
     * （跨模块只调 Service，不注入对方 Mapper，手册 §3.2 铁律）。
     */
    @Override
    @Transactional
    public GeoReportVO reportGeo(Long workerUserId, ReportGeoRequest request) {
        WorkerProfile profile = getByUserId(workerUserId);
        if (profile == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND, "陪诊师档案不存在，请先完成入驻");
        }

        BigDecimal lat = GeoUtil.parseCoordinate(request == null ? null : request.getGpsLat());
        BigDecimal lng = GeoUtil.parseCoordinate(request == null ? null : request.getGpsLng());
        if (!GeoUtil.isValid(lat, lng)) {
            // 明确文案便于前端区分「没传」与「越界」，比通用 1001 更好排查
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "经纬度不合法，需为合法范围内且保留 6 位小数的坐标");
        }

        String geoHash = GeoUtil.geoHash(lat, lng, GEO_HASH_PRECISION);
        workerProfileMapper.update(null, new LambdaUpdateWrapper<WorkerProfile>()
                .eq(WorkerProfile::getUserId, workerUserId)
                .set(WorkerProfile::getGpsLat, lat)
                .set(WorkerProfile::getGpsLng, lng)
                .set(WorkerProfile::getGeoHash, geoHash)
                .set(WorkerProfile::getGeoUpdatedAt, LocalDateTime.now()));

        GeoReportVO vo = new GeoReportVO();
        vo.setGeoHash(geoHash);
        // 红点数：查询失败不应让上报接口整体失败（定位上报是高频心跳，
        // 失败会导致前端反复重试）。降级为 0，前端仍可照常展示大厅。
        try {
            vo.setNearbyOrderCount((int) orderService.countNearbyHallOrders(workerUserId, lat, lng));
        } catch (Exception e) {
            log.warn("[worker] 查询附近可接单数失败，降级为 0 workerUserId={}: {}", workerUserId, e.getMessage());
            vo.setNearbyOrderCount(0);
        }
        return vo;
    }

    // ---------------- §3.4 / §3.5 入驻申请 ----------------

    /**
     * §3.4 入驻申请。
     *
     * <p>状态机（契约备注 2）：无记录 → 建 PENDING；PENDING 重复提交 → 5002；
     * PASS → 1003（已通过不再受理）；REJECT → 允许重新提交，覆盖原记录并重置 PENDING。
     */
    @Override
    @Transactional
    public Map<String, Object> apply(Long userId, WorkerApplyRequest request) {
        SysUser user = userService.getById(userId);
        // 手机号唯一性：该手机号已被其他账号占用则 2002（契约 §3.4 错误码）
        String phone = request.getPhone().trim();
        if (StringUtils.hasText(phone) && findByPhone(phone, userId)) {
            throw new BizException(ErrorCode.PHONE_ALREADY_BOUND);
        }

        List<String> serviceTypes = new ArrayList<>(request.getServiceTypes());
        for (String raw : serviceTypes) {
            String code = raw == null ? "" : raw.trim();
            if (!ServiceType.isValid(code)) {
                throw new BizException(ErrorCode.PARAM_ERROR, "不支持的服务类型：" + raw);
            }
        }

        WorkerProfile existing = getByUserId(userId);
        if (existing != null) {
            String status = existing.getAuditStatus();
            if (AuditStatusEnum.PASS.name().equals(status)) {
                throw new BizException(ErrorCode.FORBIDDEN, "已完成入驻，无需重复申请");
            }
            if (AuditStatusEnum.PENDING.name().equals(status)) {
                throw new BizException(ErrorCode.APPEAL_DUPLICATE, "已有待审核申请，请勿重复提交");
            }
        }

        WorkerProfile profile = existing == null ? new WorkerProfile() : existing;
        profile.setUserId(userId);
        profile.setRealName(request.getRealName().trim());
        profile.setIdCardNo(request.getIdCardNo().trim());
        profile.setHealthCertUrl(request.getHealthCertUrl().trim());
        profile.setCity(request.getCity().trim());
        profile.setServiceTypes(joinServiceTypes(serviceTypes));
        profile.setIntro(trimToNull(request.getIntro()));
        // 重新提交：重置为 PENDING 并清掉上次的审核结论，避免"驳回原因"残留
        profile.setAuditStatus(AuditStatusEnum.PENDING.name());
        profile.setAuditRemark(null);
        profile.setAuditBy(null);
        profile.setAuditTime(null);
        if (profile.getCreditScore() == null) {
            // 信用分仅在审核通过时正式生效（§9.10 初始化 100），此处先落初值避免列 NOT NULL 冲突
            profile.setCreditScore(DEFAULT_CREDIT);
        }

        if (existing == null) {
            workerProfileMapper.insert(profile);
        } else {
            workerProfileMapper.updateById(profile);
        }
        // 联系手机号同步到 sys_user（未绑定过时补上），便于后续派单联系
        if (StringUtils.hasText(phone) && !StringUtils.hasText(user.getPhone())) {
            userService.bindPhoneIfAbsent(userId, phone);
        }

        log.info("[worker] 入驻申请已提交 userId={} applyId={} city={} serviceTypes={}",
                userId, profile.getId(), profile.getCity(), profile.getServiceTypes());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applyId", String.valueOf(profile.getId()));
        result.put("auditStatus", AuditStatusEnum.PENDING.name());
        return result;
    }

    @Override
    public WorkerApplyStatusVO getApplyStatus(Long userId) {
        WorkerProfile profile = getByUserId(userId);
        // 从未申请过：返回 null，由 Controller 转成空对象 {}（契约 §3.5）
        if (profile == null) {
            return null;
        }
        WorkerApplyStatusVO vo = new WorkerApplyStatusVO();
        vo.setApplyId(profile.getId());
        vo.setAuditStatus(profile.getAuditStatus());
        vo.setAuditRemark(profile.getAuditRemark());
        vo.setAuditTime(profile.getAuditTime());
        // 信用分只在通过后有意义；未通过时不下发（前端据 auditStatus 决定是否展示）
        if (AuditStatusEnum.PASS.name().equals(profile.getAuditStatus())) {
            vo.setCreditScore(profile.getCreditScore());
        }
        vo.setSubmittedAt(profile.getCreatedAt());
        return vo;
    }

    /** 手机号是否已被「其它」账号绑定（排除自己，避免重复提交时误判） */
    private boolean findByPhone(String phone, Long selfUserId) {
        List<SysUser> users = userService.listByPhones(java.util.List.of(phone));
        for (SysUser user : users) {
            if (!selfUserId.equals(user.getId())) {
                return true;
            }
        }
        return false;
    }

    /** 去空白后为空则返回 null，避免落库空串 */
    private String trimToNull(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    public WorkerProfileVO getProfile(Long workerUserId) {
        return toProfileVO(requireProfile(workerUserId));
    }

    @Override
    @Transactional
    public WorkerProfileVO updateProfile(Long workerUserId, UpdateWorkerProfileRequest request) {
        WorkerProfile profile = requireProfile(workerUserId);
        if (AuditStatusEnum.PENDING.name().equals(profile.getAuditStatus())) {
            throw new BizException(ErrorCode.WORKER_AUDIT_NOT_PASS, "审核中，暂不可修改资料");
        }

        LambdaUpdateWrapper<WorkerProfile> update = new LambdaUpdateWrapper<WorkerProfile>()
                .eq(WorkerProfile::getUserId, workerUserId);
        boolean changed = false;

        if (request.getCity() != null) {
            update.set(WorkerProfile::getCity, request.getCity().trim());
            changed = true;
        }
        if (request.getServiceTypes() != null) {
            if (request.getServiceTypes().isEmpty()) {
                throw new BizException(ErrorCode.PARAM_ERROR, "serviceTypes 至少 1 项");
            }
            update.set(WorkerProfile::getServiceTypes, joinServiceTypes(request.getServiceTypes()));
            changed = true;
        }
        if (request.getIntro() != null) {
            String intro = request.getIntro().trim();
            String hit = SensitiveWords.firstHit(intro);
            if (hit != null) {
                throw new BizException(ErrorCode.PARAM_ERROR, "intro 含不适当内容：" + hit);
            }
            update.set(WorkerProfile::getIntro, intro);
            changed = true;
        }
        if (request.getHealthCertUrl() != null) {
            update.set(WorkerProfile::getHealthCertUrl, request.getHealthCertUrl().trim());
            changed = true;
        }
        // geo_hash 由阶段 5 的定位上报（§7.4）维护，city 变更不产生坐标，故此处不刷新
        if (changed) {
            workerProfileMapper.update(null, update);
        }
        return toProfileVO(requireProfile(workerUserId));
    }

    @Override
    public void updateReviewStats(Long workerUserId, int reviewCount, int goodCount, BigDecimal avgStar) {
        int goodRate = reviewCount <= 0 ? 100
                : BigDecimal.valueOf(goodCount * 100L)
                .divide(BigDecimal.valueOf(reviewCount), 0, RoundingMode.HALF_UP)
                .intValue();
        workerProfileMapper.update(null, new LambdaUpdateWrapper<WorkerProfile>()
                .eq(WorkerProfile::getUserId, workerUserId)
                .set(WorkerProfile::getReviewCount, Math.max(0, reviewCount))
                .set(WorkerProfile::getGoodRate, Math.max(0, Math.min(100, goodRate)))
                .set(WorkerProfile::getAvgStar, avgStar == null
                        ? DEFAULT_AVG_STAR : avgStar.setScale(1, RoundingMode.HALF_UP)));
    }

    @Override
    public void incrementServiceCount(Long workerUserId) {
        if (workerUserId == null) {
            return;
        }
        workerProfileMapper.update(null, new LambdaUpdateWrapper<WorkerProfile>()
                .eq(WorkerProfile::getUserId, workerUserId)
                .setSql("service_count = service_count + 1"));
    }

    private WorkerProfile requireProfile(Long workerUserId) {
        WorkerProfile profile = getByUserId(workerUserId);
        if (profile == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return profile;
    }

    private WorkerProfileVO toProfileVO(WorkerProfile profile) {
        WorkerProfileVO vo = new WorkerProfileVO();
        vo.setWorkerUserId(String.valueOf(profile.getUserId()));
        vo.setRealName(profile.getRealName());
        vo.setIdCardMasked(maskIdCard(profile.getIdCardNo()));
        vo.setIdCardVerified(StringUtils.hasText(profile.getIdCardNo()));
        vo.setHealthCertUrl(profile.getHealthCertUrl());
        vo.setAuditStatus(profile.getAuditStatus());
        vo.setAuditRemark(profile.getAuditRemark());
        vo.setAuditTime(profile.getAuditTime());
        int score = profile.getCreditScore() == null ? DEFAULT_CREDIT : profile.getCreditScore();
        vo.setCreditScore(score);
        vo.setCreditLevel(CreditLevel.of(score).name());
        vo.setCity(profile.getCity());
        List<String> types = splitCsv(profile.getServiceTypes());
        vo.setServiceTypes(types);
        vo.setServiceTypeNames(serviceTypeNames(types));
        vo.setIntro(profile.getIntro());
        vo.setCanEdit(!AuditStatusEnum.PENDING.name().equals(profile.getAuditStatus()));
        vo.setJoinedAt(profile.getAuditTime());
        vo.setUpdatedAt(profile.getUpdatedAt());
        vo.setGoodRate(profile.getGoodRate() == null ? 100 : profile.getGoodRate());
        vo.setReviewCount(profile.getReviewCount() == null ? 0 : profile.getReviewCount());
        vo.setServiceCount(profile.getServiceCount() == null ? 0 : profile.getServiceCount());
        vo.setAvgStar(oneDecimal(profile.getAvgStar()));
        return vo;
    }

    static String oneDecimal(BigDecimal value) {
        return (value == null ? DEFAULT_AVG_STAR : value).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * §7.1 工作台首页聚合。一次请求返回首页全部数据。
     *
     * <p>契约要求：{@code auditStatus != PASS} 时统计字段仍返回默认值（0 / "0.00"），
     * 前端只按 auditStatus 决定渲染哪套 UI，不必判空。
     */
    @Override
    public WorkerOverviewVO getOverview(Long workerUserId) {
        WorkerProfile profile = requireProfile(workerUserId);
        SysUser user = userService.getById(workerUserId);

        WorkerOverviewVO vo = new WorkerOverviewVO();
        vo.setWorkerUserId(workerUserId);
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setAuditStatus(profile.getAuditStatus());
        vo.setAuditRemark(profile.getAuditRemark());
        vo.setServerTime(LocalDateTime.now());

        int score = profile.getCreditScore() == null ? DEFAULT_CREDIT : profile.getCreditScore();
        vo.setCreditScore(score);
        vo.setCreditLevel(CreditLevel.of(score).name());

        // 非 PASS 时统计字段给默认值，不抛异常、返回空数据
        if (!AuditStatusEnum.PASS.name().equals(profile.getAuditStatus())) {
            vo.setAccepting(false);
            vo.setActiveCount(0L);
            vo.setActiveLimit(0);
            vo.setHallCount(0L);
            vo.setTodayCount(0L);
            vo.setMonthEarning(BigDecimal.ZERO);
            vo.setMonthOrderCount(0L);
            vo.setPendingWithdraw(BigDecimal.ZERO);
            vo.setNewReviewCount(0L);
            return vo;
        }

        long activeCount = orderService.countActive(workerUserId);
        int limit = score < lowCreditThreshold ? maxActiveOrdersLowCredit : maxActiveOrders;
        vo.setActiveCount(activeCount);
        vo.setActiveLimit(limit);
        // 可接单 = 信用分达标 且 未达接单上限（与 §7.6 抢单校验保持一致）
        vo.setAccepting(score >= minCreditAccept && activeCount < limit);
        vo.setHallCount(orderService.countHall());
        vo.setTodayCount(orderService.countTodayPending(workerUserId));
        vo.setMonthOrderCount(orderService.countMonthCompleted(workerUserId));
        vo.setNewReviewCount(0L);

        // 本月已结算收益与提现中金额取自钱包（V14 累计列）
        try {
            WalletVO account = walletService.getAccountVO(workerUserId);
            vo.setMonthEarning(BigDecimal.ZERO);
            vo.setPendingWithdraw(account.getWithdrawing());
        } catch (Exception e) {
            log.warn("[worker] 查询钱包失败，overview 用默认值 workerUserId={}: {}", workerUserId, e.getMessage());
            vo.setMonthEarning(BigDecimal.ZERO);
            vo.setPendingWithdraw(BigDecimal.ZERO);
        }

        // 最需要处理的一单：优先服务中，其次已接单（§7.1 processingOrder）
        vo.setProcessingOrder(findProcessingOrder(workerUserId));
        return vo;
    }

    /** 取最需要处理的一单：30 服务中优先，其次 20 已接单；无则返回 null（VO 键缺失） */
    private OrderCardVO findProcessingOrder(Long workerUserId) {
        OrderQuery query = new OrderQuery();
        query.setStatusGroup("PROCESSING");
        query.setPageNum(1);
        query.setPageSize(1);
        IPage<OrderCardVO> page = orderService.pageWorkerOrderCards(workerUserId, query);
        return page.getRecords().isEmpty() ? null : page.getRecords().get(0);
    }

    static String maskIdCard(String idCardNo) {
        if (!StringUtils.hasText(idCardNo)) {
            return null;
        }
        String value = idCardNo.trim();
        if (value.length() <= 8) {
            return value.charAt(0) + "****";
        }
        return value.substring(0, 4) + "*".repeat(value.length() - 8) + value.substring(value.length() - 4);
    }

    static List<String> splitCsv(String csv) {
        if (!StringUtils.hasText(csv)) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    static List<String> serviceTypeNames(List<String> codes) {
        List<String> names = new ArrayList<>(codes.size());
        for (String code : codes) {
            for (ServiceType type : ServiceType.values()) {
                if (type.name().equals(code)) {
                    names.add(type.getLabel());
                    break;
                }
            }
        }
        return names;
    }

    private String joinServiceTypes(List<String> serviceTypes) {
        List<String> codes = new ArrayList<>(serviceTypes.size());
        for (String raw : serviceTypes) {
            String code = raw == null ? "" : raw.trim();
            if (!ServiceType.isValid(code)) {
                throw new BizException(ErrorCode.PARAM_ERROR, "serviceTypes 含非法值：" + raw);
            }
            if (!codes.contains(code)) {
                codes.add(code);
            }
        }
        return String.join(",", codes);
    }
}
