package com.dsc.medipartner.module.review.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.service.OrderService;
import com.dsc.medipartner.module.review.domain.dto.AppendReviewRequest;
import com.dsc.medipartner.module.review.domain.dto.CreateReviewRequest;
import com.dsc.medipartner.module.review.domain.dto.ReplyReviewRequest;
import com.dsc.medipartner.module.review.domain.entity.Review;
import com.dsc.medipartner.module.review.domain.row.ReviewRow;
import com.dsc.medipartner.module.review.domain.row.ReviewStatsRow;
import com.dsc.medipartner.module.review.domain.vo.ReviewVO;
import com.dsc.medipartner.module.review.domain.vo.WorkerReviewVO;
import com.dsc.medipartner.module.review.mapper.ReviewMapper;
import com.dsc.medipartner.module.user.domain.entity.SysUser;
import com.dsc.medipartner.module.user.domain.enums.CreditChangeType;
import com.dsc.medipartner.module.user.service.CreditService;
import com.dsc.medipartner.module.user.service.UserService;
import com.dsc.medipartner.module.user.service.WorkerService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReviewServiceImpl 纯单测：一单一评闸门、好评/差评信用分联动、追评与回复的窗口与幂等守卫。
 */
class ReviewServiceImplTest {

    private static final long USER_ID = 9L;
    private static final long WORKER_ID = 7L;
    private static final String ORDER_NO = "MP20260921000000123456";

    private ReviewMapper reviewMapper;
    private OrderService orderService;
    private UserService userService;
    private WorkerService workerService;
    private CreditService creditService;
    private ReviewServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Review.class);
    }

    @BeforeEach
    void setUp() {
        reviewMapper = mock(ReviewMapper.class);
        orderService = mock(OrderService.class);
        userService = mock(UserService.class);
        workerService = mock(WorkerService.class);
        creditService = mock(CreditService.class);
        service = new ReviewServiceImpl(reviewMapper, orderService, userService, workerService, creditService);
    }

    private ServiceOrder order(int status) {
        ServiceOrder order = new ServiceOrder();
        order.setId(1L);
        order.setOrderNo(ORDER_NO);
        order.setUserId(USER_ID);
        order.setWorkerId(WORKER_ID);
        order.setOrderStatus(status);
        return order;
    }

    private SysUser user(long id, String nickname) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setNickname(nickname);
        user.setAvatar("/files/AVATAR/u.png");
        return user;
    }

    private CreateReviewRequest createRequest(int star) {
        CreateReviewRequest request = new CreateReviewRequest();
        request.setStar(star);
        request.setContent("服务很周到");
        request.setTags(List.of("PUNCTUAL"));
        request.setAnonymous(false);
        return request;
    }

    @Test
    void createRejectsNonOwnerWith3008() {
        ServiceOrder order = order(OrderState.COMPLETED.getCode());
        order.setUserId(123L);
        when(orderService.getByOrderNo(ORDER_NO)).thenReturn(order);
        assertThatThrownBy(() -> service.create(USER_ID, ORDER_NO, createRequest(5)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_BELONG_TO_USER));
    }

    @Test
    void createRejectsNonCompletedWith5003() {
        when(orderService.getByOrderNo(ORDER_NO)).thenReturn(order(OrderState.IN_SERVICE.getCode()));
        assertThatThrownBy(() -> service.create(USER_ID, ORDER_NO, createRequest(5)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REVIEW_NOT_ALLOWED));
    }

    @Test
    void createRejectsDuplicateWith5003() {
        when(orderService.getByOrderNo(ORDER_NO)).thenReturn(order(OrderState.COMPLETED.getCode()));
        when(reviewMapper.selectOne(any())).thenReturn(new Review());
        assertThatThrownBy(() -> service.create(USER_ID, ORDER_NO, createRequest(5)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REVIEW_NOT_ALLOWED));
        verify(reviewMapper, never()).insert(any(Review.class));
    }

    @Test
    void createGoodReviewBumpsPositiveCredit() {
        when(orderService.getByOrderNo(ORDER_NO)).thenReturn(order(OrderState.COMPLETED.getCode()));
        when(reviewMapper.selectOne(any())).thenReturn(null);
        when(reviewMapper.insert(any(Review.class))).thenAnswer(inv -> {
            Review r = inv.getArgument(0);
            r.setId(777L);
            return 1;
        });
        ReviewStatsRow stats = new ReviewStatsRow();
        stats.setReviewCount(1);
        stats.setGoodCount(1);
        stats.setAvgStar(new BigDecimal("5.0"));
        when(reviewMapper.stats(WORKER_ID)).thenReturn(stats);
        when(userService.listByIds(any())).thenReturn(List.of(user(USER_ID, "小明")));

        ReviewVO vo = service.create(USER_ID, ORDER_NO, createRequest(5));

        verify(workerService).updateReviewStats(eq(WORKER_ID), eq(1), eq(1), any());
        verify(creditService).change(WORKER_ID, CreditChangeType.POSITIVE_REVIEW, ORDER_NO, 777L);
        assertThat(vo.getReviewId()).isEqualTo("777");
        assertThat(vo.getStar()).isEqualTo(5);
        assertThat(vo.getReviewerRole()).isEqualTo("USER");
        assertThat(vo.getTagNames()).contains("准时到达");
    }

    @Test
    void createBadReviewAppliesNegativeCredit() {
        when(orderService.getByOrderNo(ORDER_NO)).thenReturn(order(OrderState.COMPLETED.getCode()));
        when(reviewMapper.selectOne(any())).thenReturn(null);
        when(reviewMapper.insert(any(Review.class))).thenAnswer(inv -> {
            Review r = inv.getArgument(0);
            r.setId(888L);
            return 1;
        });
        when(reviewMapper.stats(WORKER_ID)).thenReturn(null);
        when(userService.listByIds(any())).thenReturn(List.of(user(USER_ID, "小明")));

        service.create(USER_ID, ORDER_NO, createRequest(1));

        verify(creditService).change(WORKER_ID, CreditChangeType.NEGATIVE_REVIEW, ORDER_NO, 888L);
    }

    @Test
    void appendRejectsWhenNoReviewWith5003() {
        when(orderService.getByOrderNo(ORDER_NO)).thenReturn(order(OrderState.COMPLETED.getCode()));
        when(reviewMapper.selectOne(any())).thenReturn(null);
        AppendReviewRequest request = new AppendReviewRequest();
        request.setContent("补充一下");
        assertThatThrownBy(() -> service.append(USER_ID, ORDER_NO, request))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REVIEW_NOT_ALLOWED));
    }

    @Test
    void appendRejectsSecondAppendWith5005() {
        when(orderService.getByOrderNo(ORDER_NO)).thenReturn(order(OrderState.COMPLETED.getCode()));
        Review existing = new Review();
        existing.setId(777L);
        existing.setAppendContent("已经追评过");
        existing.setCreatedAt(LocalDateTime.now());
        when(reviewMapper.selectOne(any())).thenReturn(existing);
        AppendReviewRequest request = new AppendReviewRequest();
        request.setContent("再补充");
        assertThatThrownBy(() -> service.append(USER_ID, ORDER_NO, request))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REVIEW_APPEND_USED));
    }

    @Test
    void replyRejectsNonOwnerWith3008() {
        Review review = new Review();
        review.setId(777L);
        review.setWorkerId(999L);
        review.setReviewStatus(1);
        when(reviewMapper.selectById(777L)).thenReturn(review);
        ReplyReviewRequest request = new ReplyReviewRequest();
        request.setContent("感谢");
        assertThatThrownBy(() -> service.reply(WORKER_ID, 777L, request))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_BELONG_TO_USER));
    }

    @Test
    void replyRejectsAlreadyRepliedWith5004() {
        Review review = new Review();
        review.setId(777L);
        review.setWorkerId(WORKER_ID);
        review.setReviewStatus(1);
        review.setReplyContent("已回复");
        review.setCreatedAt(LocalDateTime.now());
        when(reviewMapper.selectById(777L)).thenReturn(review);
        ReplyReviewRequest request = new ReplyReviewRequest();
        request.setContent("感谢");
        assertThatThrownBy(() -> service.reply(WORKER_ID, 777L, request))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REVIEW_ALREADY_REPLIED));
    }

    @Test
    void replySuccessReturnsWorkerVo() {
        Review review = new Review();
        review.setId(777L);
        review.setWorkerId(WORKER_ID);
        review.setReviewerId(USER_ID);
        review.setReviewStatus(1);
        review.setCreatedAt(LocalDateTime.now());
        when(reviewMapper.selectById(777L)).thenReturn(review);
        when(reviewMapper.update(any(), any())).thenReturn(1);
        ReviewRow row = new ReviewRow();
        row.setReviewId(777L);
        row.setReviewerId(USER_ID);
        row.setOrderNo(ORDER_NO);
        row.setStar(5);
        row.setCreatedAt(LocalDateTime.now());
        row.setReplyContent("感谢");
        when(reviewMapper.rowById(777L)).thenReturn(row);
        when(userService.listByIds(any())).thenReturn(List.of(user(USER_ID, "小明")));
        ReplyReviewRequest request = new ReplyReviewRequest();
        request.setContent("感谢");

        WorkerReviewVO vo = service.reply(WORKER_ID, 777L, request);

        assertThat(vo).isNotNull();
        assertThat(vo.getReviewId()).isEqualTo("777");
        verify(reviewMapper).update(any(), any());
    }
}