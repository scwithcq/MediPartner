package com.dsc.medipartner.module.review.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.util.SensitiveWords;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.domain.enums.ServiceType;
import com.dsc.medipartner.module.order.service.OrderService;
import com.dsc.medipartner.module.review.domain.dto.AppendReviewRequest;
import com.dsc.medipartner.module.review.domain.dto.CreateReviewRequest;
import com.dsc.medipartner.module.review.domain.dto.ReplyReviewRequest;
import com.dsc.medipartner.module.review.domain.dto.ReviewQuery;
import com.dsc.medipartner.module.review.domain.dto.WorkerReviewQuery;
import com.dsc.medipartner.module.review.domain.entity.Review;
import com.dsc.medipartner.module.review.domain.enums.ReviewTag;
import com.dsc.medipartner.module.review.domain.enums.ReviewerRole;
import com.dsc.medipartner.module.review.domain.row.ReviewRow;
import com.dsc.medipartner.module.review.domain.row.ReviewStatsRow;
import com.dsc.medipartner.module.review.domain.row.StarCountRow;
import com.dsc.medipartner.module.review.domain.vo.OrderReviewVO;
import com.dsc.medipartner.module.review.domain.vo.PublicReviewVO;
import com.dsc.medipartner.module.review.domain.vo.ReviewVO;
import com.dsc.medipartner.module.review.domain.vo.WorkerReviewVO;
import com.dsc.medipartner.module.review.mapper.ReviewMapper;
import com.dsc.medipartner.module.review.service.ReviewService;
import com.dsc.medipartner.module.user.domain.entity.SysUser;
import com.dsc.medipartner.module.user.domain.enums.CreditChangeType;
import com.dsc.medipartner.module.user.service.CreditService;
import com.dsc.medipartner.module.user.service.UserService;
import com.dsc.medipartner.module.user.service.WorkerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 评价体系实现（契约 §5.11~§5.13、§6.3、§7.20~§7.21）。
 * 关键口径：一单一评按角色维度（uk_order_role 兜底）、好评率按 star>=4 计、
 * 追评与回复都是 7 天窗口且各只允许一次、匿名脱敏一律在服务端完成。
 * 信用分联动只走 CreditService，本类不直接改 worker_profile.credit_score。
 */
@Slf4j
@Service
public class ReviewServiceImpl implements ReviewService {

    /** 追评与回复共用 7 天窗口（契约 §5.13、§7.21） */
    private static final int WINDOW_DAYS = 7;
    private static final String ANONYMOUS_NICKNAME = "匿名用户";
    private static final String DEFAULT_AVATAR = "/files/AVATAR/default.png";
    private static final int GOOD_STAR_MIN = 4;
    private static final int BAD_STAR_MAX = 2;
    /** review_status = 1 生效中；0 为被管理端仲裁屏蔽（§9.23） */
    private static final int REVIEW_STATUS_ACTIVE = 1;

    private final ReviewMapper reviewMapper;
    private final OrderService orderService;
    private final UserService userService;
    private final WorkerService workerService;
    private final CreditService creditService;

    public ReviewServiceImpl(ReviewMapper reviewMapper, OrderService orderService, UserService userService,
                             WorkerService workerService, CreditService creditService) {
        this.reviewMapper = reviewMapper;
        this.orderService = orderService;
        this.userService = userService;
        this.workerService = workerService;
        this.creditService = creditService;
    }

    @Override
    @Transactional
    public ReviewVO create(Long userId, String orderNo, CreateReviewRequest request) {
        ServiceOrder order = requireOrder(orderNo);
        if (!userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.ORDER_NOT_BELONG_TO_USER);
        }
        if (order.getOrderStatus() == null || order.getOrderStatus() != OrderState.COMPLETED.getCode()) {
            throw new BizException(ErrorCode.REVIEW_NOT_ALLOWED);
        }
        if (order.getWorkerId() == null) {
            throw new BizException(ErrorCode.REVIEW_NOT_ALLOWED, "该订单尚无陪诊师，无法评价");
        }
        if (findByOrder(orderNo, ReviewerRole.USER) != null) {
            throw new BizException(ErrorCode.REVIEW_NOT_ALLOWED, "该订单已评价过");
        }

        String content = trimToNull(request.getContent());
        String hit = SensitiveWords.firstHit(content);
        if (hit != null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "评价内容含不适当内容：" + hit);
        }

        Review review = new Review();
        review.setOrderNo(orderNo);
        review.setWorkerId(order.getWorkerId());
        review.setReviewerId(userId);
        review.setReviewerRole(ReviewerRole.USER.name());
        review.setStar(request.getStar());
        review.setContent(content);
        review.setTags(join(normalizeTags(request.getTags())));
        review.setImageUrls(join(trimList(request.getImageUrls())));
        review.setAnonymous(Boolean.TRUE.equals(request.getAnonymous()));
        review.setReviewStatus(1);
        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            // uk_order_role 兜底：并发下的重复评价
            throw new BizException(ErrorCode.REVIEW_NOT_ALLOWED, "该订单已评价过");
        }

        refreshWorkerStats(order.getWorkerId());
        applyCredit(order.getWorkerId(), orderNo, review);
        return toReviewVO(review, loadUsers(List.of(userId)));
    }

    @Override
    public OrderReviewVO getOrderReviews(Long userId, String orderNo) {
        ServiceOrder order = requireOrder(orderNo);
        if (!userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.ORDER_NOT_BELONG_TO_USER);
        }
        Review mine = findByOrder(orderNo, ReviewerRole.USER);
        Review fromWorker = findByOrder(orderNo, ReviewerRole.WORKER);

        List<Long> reviewerIds = new ArrayList<>(2);
        if (mine != null) {
            reviewerIds.add(mine.getReviewerId());
        }
        if (fromWorker != null) {
            reviewerIds.add(fromWorker.getReviewerId());
        }
        Map<Long, SysUser> users = loadUsers(reviewerIds);

        OrderReviewVO vo = new OrderReviewVO();
        vo.setMyReview(mine == null ? null : toReviewVO(mine, users));
        vo.setWorkerReview(fromWorker == null ? null : toReviewVO(fromWorker, users));
        return vo;
    }

    @Override
    @Transactional
    public ReviewVO append(Long userId, String orderNo, AppendReviewRequest request) {
        ServiceOrder order = requireOrder(orderNo);
        if (!userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.ORDER_NOT_BELONG_TO_USER);
        }
        Review review = findByOrder(orderNo, ReviewerRole.USER);
        if (review == null) {
            throw new BizException(ErrorCode.REVIEW_NOT_ALLOWED, "请先完成评价再追评");
        }
        if (StringUtils.hasText(review.getAppendContent())) {
            throw new BizException(ErrorCode.REVIEW_APPEND_USED);
        }
        if (isWindowExpired(review.getCreatedAt())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "追评时限已过");
        }
        String content = request.getContent().trim();
        String hit = SensitiveWords.firstHit(content);
        if (hit != null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "追评内容含不适当内容：" + hit);
        }

        LocalDateTime appendAt = LocalDateTime.now();
        reviewMapper.update(null, new LambdaUpdateWrapper<Review>()
                .eq(Review::getId, review.getId())
                .isNull(Review::getAppendContent)
                .set(Review::getAppendContent, content)
                .set(Review::getAppendAt, appendAt));

        review.setAppendContent(content);
        review.setAppendAt(appendAt);
        return toReviewVO(review, loadUsers(List.of(userId)));
    }

    @Override
    public IPage<PublicReviewVO> pagePublicReviews(Long workerUserId, ReviewQuery query) {
        Page<ReviewRow> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<ReviewRow> rows = reviewMapper.pagePublicReviews(page, workerUserId, query);
        Map<Long, SysUser> users = loadUsers(reviewerIds(rows.getRecords()));
        List<PublicReviewVO> records = rows.getRecords().stream()
                .map(row -> toPublicVO(row, users))
                .toList();
        return mapPage(rows, records);
    }

    @Override
    public List<PublicReviewVO> recentPublicReviews(Long workerUserId, int limit) {
        List<ReviewRow> rows = reviewMapper.recentPublicReviews(workerUserId, limit);
        Map<Long, SysUser> users = loadUsers(reviewerIds(rows));
        return rows.stream().map(row -> toPublicVO(row, users)).toList();
    }

    @Override
    public Map<String, Integer> starDistribution(Long workerUserId) {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        for (int star = 5; star >= 1; star--) {
            distribution.put(String.valueOf(star), 0);
        }
        for (StarCountRow row : reviewMapper.countByStar(workerUserId)) {
            if (row.getStar() != null && distribution.containsKey(String.valueOf(row.getStar()))) {
                distribution.put(String.valueOf(row.getStar()), row.getCnt() == null ? 0 : row.getCnt());
            }
        }
        return distribution;
    }

    @Override
    public IPage<WorkerReviewVO> pageWorkerReviews(Long workerId, WorkerReviewQuery query) {
        Page<ReviewRow> page = new Page<>(query.getPageNum(), query.getPageSize());
        LocalDateTime replyDeadline = LocalDateTime.now().minusDays(WINDOW_DAYS);
        IPage<ReviewRow> rows = reviewMapper.pageWorkerReviews(page, workerId, query, replyDeadline);
        Map<Long, SysUser> users = loadUsers(reviewerIds(rows.getRecords()));
        List<WorkerReviewVO> records = rows.getRecords().stream()
                .map(row -> toWorkerVO(row, users, replyDeadline))
                .toList();
        return mapPage(rows, records);
    }

    @Override
    @Transactional
    public WorkerReviewVO reply(Long workerId, Long reviewId, ReplyReviewRequest request) {
        Review review = reviewMapper.selectById(reviewId);
        if (review == null || review.getReviewStatus() == null || review.getReviewStatus() != 1) {
            throw new BizException(ErrorCode.PARAM_ERROR, "评价不存在");
        }
        if (!workerId.equals(review.getWorkerId())) {
            throw new BizException(ErrorCode.ORDER_NOT_BELONG_TO_USER);
        }
        if (StringUtils.hasText(review.getReplyContent())) {
            throw new BizException(ErrorCode.REVIEW_ALREADY_REPLIED);
        }
        if (isWindowExpired(review.getCreatedAt())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "回复时限已过");
        }
        String content = request.getContent().trim();
        String hit = SensitiveWords.firstHit(content);
        if (hit != null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "回复含不适当内容：" + hit);
        }

        LocalDateTime replyAt = LocalDateTime.now();
        // isNull 条件是并发下的第二道防线，配合 uk 无关，仅防止同一回复被写两次
        int updated = reviewMapper.update(null, new LambdaUpdateWrapper<Review>()
                .eq(Review::getId, reviewId)
                .isNull(Review::getReplyContent)
                .set(Review::getReplyContent, content)
                .set(Review::getReplyAt, replyAt));
        if (updated != 1) {
            throw new BizException(ErrorCode.REVIEW_ALREADY_REPLIED);
        }
        // 契约要求把 WORKER_REPLY 记入 order_status_log，该表属阶段 1.5（V6），迁移落地后在此补写

        ReviewRow row = reviewMapper.rowById(reviewId);
        if (row == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "评价不存在");
        }
        return toWorkerVO(row, loadUsers(List.of(row.getReviewerId())),
                LocalDateTime.now().minusDays(WINDOW_DAYS));
    }

    /** 评价写入后重算并回写 worker_profile 冗余统计列（§5.11 联动） */
    private void refreshWorkerStats(Long workerId) {
        ReviewStatsRow stats = reviewMapper.stats(workerId);
        int reviewCount = stats == null || stats.getReviewCount() == null ? 0 : stats.getReviewCount();
        int goodCount = stats == null || stats.getGoodCount() == null ? 0 : stats.getGoodCount();
        BigDecimal avgStar = stats == null ? null : stats.getAvgStar();
        workerService.updateReviewStats(workerId, reviewCount, goodCount, avgStar);
    }

    /** star>=4 好评 +1，star<=2 差评 -2；3 星中性不动分（契约 §7.16 备注 1） */
    private void applyCredit(Long workerId, String orderNo, Review review) {
        int star = review.getStar() == null ? 0 : review.getStar();
        if (star >= GOOD_STAR_MIN) {
            creditService.change(workerId, CreditChangeType.POSITIVE_REVIEW, orderNo, review.getId());
        } else if (star <= BAD_STAR_MAX) {
            creditService.change(workerId, CreditChangeType.NEGATIVE_REVIEW, orderNo, review.getId());
        }
    }

    private Review findByOrder(String orderNo, ReviewerRole role) {
        return reviewMapper.selectOne(new LambdaQueryWrapper<Review>()
                .eq(Review::getOrderNo, orderNo)
                .eq(Review::getReviewerRole, role.name())
                .last("LIMIT 1"));
    }

    private ServiceOrder requireOrder(String orderNo) {
        ServiceOrder order = orderService.getByOrderNo(orderNo);
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    private boolean isWindowExpired(LocalDateTime createdAt) {
        return createdAt == null || createdAt.plusDays(WINDOW_DAYS).isBefore(LocalDateTime.now());
    }

    private ReviewVO toReviewVO(Review review, Map<Long, SysUser> users) {
        boolean anonymous = Boolean.TRUE.equals(review.getAnonymous());
        SysUser reviewer = users.get(review.getReviewerId());
        ReviewVO vo = new ReviewVO();
        vo.setReviewId(String.valueOf(review.getId()));
        vo.setOrderNo(review.getOrderNo());
        vo.setReviewerRole(review.getReviewerRole());
        vo.setReviewerName(anonymous ? ANONYMOUS_NICKNAME : nicknameOf(reviewer));
        vo.setReviewerAvatar(anonymous ? DEFAULT_AVATAR : avatarOf(reviewer));
        vo.setStar(review.getStar());
        vo.setContent(review.getContent());
        List<String> codes = split(review.getTags());
        vo.setTags(codes);
        vo.setTagNames(tagNames(codes));
        vo.setImageUrls(split(review.getImageUrls()));
        vo.setAnonymous(anonymous);
        vo.setAppendContent(review.getAppendContent());
        vo.setAppendAt(review.getAppendAt());
        vo.setReplyContent(review.getReplyContent());
        vo.setReplyAt(review.getReplyAt());
        vo.setCreatedAt(review.getCreatedAt());
        return vo;
    }

    private PublicReviewVO toPublicVO(ReviewRow row, Map<Long, SysUser> users) {
        PublicReviewVO vo = new PublicReviewVO();
        fillPublic(vo, row, users);
        return vo;
    }

    private WorkerReviewVO toWorkerVO(ReviewRow row, Map<Long, SysUser> users, LocalDateTime replyDeadline) {
        WorkerReviewVO vo = new WorkerReviewVO();
        fillPublic(vo, row, users);
        vo.setOrderNo(row.getOrderNo());
        vo.setOrderServiceTime(row.getOrderServiceTime());
        boolean replied = StringUtils.hasText(row.getReplyContent());
        boolean inWindow = row.getCreatedAt() != null && !row.getCreatedAt().isBefore(replyDeadline);
        vo.setCanReply(!replied && inWindow);
        // 恶意差评申诉属阶段 4，此阶段恒为 false
        vo.setCanAppeal(false);
        vo.setImpactScore(signed(row.getImpactScore()));
        return vo;
    }

    private void fillPublic(PublicReviewVO vo, ReviewRow row, Map<Long, SysUser> users) {
        boolean anonymous = Boolean.TRUE.equals(row.getAnonymous());
        SysUser reviewer = users.get(row.getReviewerId());
        vo.setReviewId(String.valueOf(row.getReviewId()));
        vo.setStar(row.getStar());
        vo.setContent(row.getContent());
        vo.setImageUrls(split(row.getImageUrls()));
        vo.setAnonymous(anonymous);
        vo.setUserNickname(anonymous ? ANONYMOUS_NICKNAME : nicknameOf(reviewer));
        vo.setUserAvatar(anonymous ? DEFAULT_AVATAR : avatarOf(reviewer));
        // §6.3 的 tags 直接给中文名，与 §5.12 的 tags(码值)+tagNames(中文) 口径不同，按契约分别下发
        vo.setTags(tagNames(split(row.getTags())));
        vo.setReplyContent(row.getReplyContent());
        vo.setReplyAt(row.getReplyAt());
        vo.setAppendContent(row.getAppendContent());
        vo.setAppendAt(row.getAppendAt());
        vo.setOrderServiceType(serviceTypeName(row.getOrderServiceType()));
        vo.setCreatedAt(row.getCreatedAt());
    }

    private Map<Long, SysUser> loadUsers(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity(), (a, b) -> a));
    }

    private List<Long> reviewerIds(List<ReviewRow> rows) {
        return rows.stream()
                .map(ReviewRow::getReviewerId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    /** 标签码值归一：非法值直接 1001，去重保序 */
    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        for (String raw : tags) {
            ReviewTag tag = ReviewTag.of(raw);
            if (tag == null) {
                throw new BizException(ErrorCode.PARAM_ERROR, "tags 含非法标签码值：" + raw);
            }
            codes.add(tag.name());
        }
        return new ArrayList<>(codes);
    }

    private List<String> tagNames(List<String> codes) {
        List<String> names = new ArrayList<>(codes.size());
        for (String code : codes) {
            ReviewTag tag = ReviewTag.of(code);
            names.add(tag == null ? code : tag.getLabel());
        }
        return names;
    }

    private String serviceTypeName(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        for (ServiceType type : ServiceType.values()) {
            if (type.name().equals(code)) {
                return type.getLabel();
            }
        }
        return code;
    }

    private String signed(Integer score) {
        if (score == null) {
            return null;
        }
        return (score > 0 ? "+" : "") + score;
    }

    private String nicknameOf(SysUser user) {
        return user == null || !StringUtils.hasText(user.getNickname()) ? ANONYMOUS_NICKNAME : user.getNickname();
    }

    private String avatarOf(SysUser user) {
        return user == null || !StringUtils.hasText(user.getAvatar()) ? DEFAULT_AVATAR : user.getAvatar();
    }

    private String join(List<String> values) {
        return values.isEmpty() ? null : String.join(",", values);
    }

    private List<String> split(String csv) {
        if (!StringUtils.hasText(csv)) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private List<String> trimList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                result.add(value.trim());
            }
        }
        return result;
    }

    private String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private <T> IPage<T> mapPage(IPage<?> source, List<T> records) {
        Page<T> page = new Page<>(source.getCurrent(), source.getSize(), source.getTotal());
        page.setRecords(records);
        return page;
    }

    /**
     * 批量查「指定角色已评价」的订单号，供 order 模块算 actions[]。
     * 一次 IN 查询，避免订单列表逐条查评价造成 N+1。
     */
    @Override
    public Set<String> findReviewedOrderNos(Collection<String> orderNos, String reviewerRole) {
        if (orderNos == null || orderNos.isEmpty() || !StringUtils.hasText(reviewerRole)) {
            return Set.of();
        }
        List<Review> rows = reviewMapper.selectList(new LambdaQueryWrapper<Review>()
                .select(Review::getOrderNo)
                .in(Review::getOrderNo, orderNos)
                .eq(Review::getReviewerRole, reviewerRole.trim().toUpperCase(Locale.ROOT))
                .eq(Review::getReviewStatus, REVIEW_STATUS_ACTIVE));
        Set<String> result = new HashSet<>(rows.size());
        for (Review row : rows) {
            result.add(row.getOrderNo());
        }
        return result;
    }
}
