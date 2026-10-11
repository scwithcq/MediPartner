package com.dsc.medipartner.module.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.util.OrderNoGenerator;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.service.OrderService;
import com.dsc.medipartner.module.ticket.domain.dto.CreateAppealRequest;
import com.dsc.medipartner.module.ticket.domain.entity.AppealTicket;
import com.dsc.medipartner.module.ticket.domain.enums.AppealType;
import com.dsc.medipartner.module.ticket.domain.vo.AppealVO;
import com.dsc.medipartner.module.ticket.domain.vo.CreateAppealVO;
import com.dsc.medipartner.module.ticket.mapper.AppealTicketMapper;
import com.dsc.medipartner.module.ticket.service.AppealService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 申诉实现（契约 §5.14、§5.15）。
 *
 * <p>「一单一诉」用<b>双保险</b>：服务层先查（给出友好的 5002），数据库
 * {@code uk_order_type} 唯一键兜底（并发下第二个请求会命中 DuplicateKeyException，同样转 5002）。
 * 只靠服务层查询在并发下会漏。
 *
 * <p>暂停自动确认：用户对 {@code 40 待确认} 的订单申诉时，落 {@code auto_confirm_paused=1} 标记，
 * 供阶段 5 的定时任务排除——避免仲裁出结果前款项已被自动结算（契约 §5.14 备注）。
 */
@Slf4j
@Service
public class AppealServiceImpl implements AppealService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String ROLE_USER = "USER";
    private static final String ROLE_SYSTEM = "SYSTEM";
    /** 证据图片上限（契约 §5.14） */
    private static final int MAX_EVIDENCE = 6;

    private final AppealTicketMapper appealMapper;
    private final OrderService orderService;

    public AppealServiceImpl(AppealTicketMapper appealMapper, @Lazy OrderService orderService) {
        this.appealMapper = appealMapper;
        this.orderService = orderService;
    }

    @Override
    @Transactional
    public CreateAppealVO create(Long userId, String orderNo, CreateAppealRequest request) {
        ServiceOrder order = orderService.getByOrderNo(orderNo);
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.ORDER_NOT_BELONG_TO_USER);
        }
        checkAppealableState(order);

        AppealType type = AppealType.of(request.getType());
        if (type == null || !type.userInitiable()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "不支持的申诉类型：" + request.getType());
        }

        BigDecimal expect = request.getExpectRefundAmount();
        if (expect != null) {
            if (expect.compareTo(BigDecimal.ZERO) < 0) {
                throw new BizException(ErrorCode.PARAM_ERROR, "期望退款金额不能为负");
            }
            // 契约 §5.14：expectRefundAmount ≤ 订单金额
            if (order.getAmount() != null && expect.compareTo(order.getAmount()) > 0) {
                throw new BizException(ErrorCode.PARAM_ERROR, "期望退款金额不得超过订单金额");
            }
        }

        AppealTicket ticket = new AppealTicket();
        ticket.setAppealNo(OrderNoGenerator.next("AP"));
        ticket.setOrderNo(orderNo);
        ticket.setApplicantId(userId);
        ticket.setApplicantRole(ROLE_USER);
        ticket.setAppealType(type.name());
        ticket.setReason(request.getReason().trim());
        ticket.setEvidenceUrls(joinUrls(request.getEvidenceUrls()));
        ticket.setExpectRefundAmount(expect);
        ticket.setContactPhone(trimToNull(request.getContactPhone()));
        ticket.setAmount(order.getAmount());
        ticket.setStatus(STATUS_PENDING);
        // 契约 §5.14 备注：40 待确认期间申诉要暂停 48h 自动确认，避免仲裁前已结算
        ticket.setAutoConfirmPaused(
                OrderState.PENDING_CONFIRM.getCode() == order.getOrderStatus() ? 1 : 0);
        ticket.setVersion(0);
        try {
            appealMapper.insert(ticket);
        } catch (DuplicateKeyException e) {
            // 并发下第二个同类型申诉被 uk_order_type 拦下
            throw new BizException(ErrorCode.APPEAL_DUPLICATE);
        }

        log.info("[appeal] 申诉已提交 orderNo={} appealNo={} type={} pausedAutoConfirm={}",
                orderNo, ticket.getAppealNo(), type, ticket.getAutoConfirmPaused());
        CreateAppealVO vo = new CreateAppealVO();
        vo.setAppealNo(ticket.getAppealNo());
        vo.setStatus(STATUS_PENDING);
        vo.setCreatedAt(ticket.getCreatedAt());
        return vo;
    }

    @Override
    public AppealVO getByOrderNo(Long userId, String orderNo) {
        ServiceOrder order = orderService.getByOrderNo(orderNo);
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.ORDER_NOT_BELONG_TO_USER);
        }
        // 取该订单最近一条用户申诉（同类型唯一，但不同类型可能共存，展示最新一条）
        AppealTicket ticket = appealMapper.selectOne(new LambdaQueryWrapper<AppealTicket>()
                .eq(AppealTicket::getOrderNo, orderNo)
                .eq(AppealTicket::getApplicantRole, ROLE_USER)
                .orderByDesc(AppealTicket::getCreatedAt)
                .orderByDesc(AppealTicket::getId)
                .last("LIMIT 1"));
        // 从未申诉过：返回 null，由 Controller 转成空对象 {}（契约 §5.15）
        return ticket == null ? null : toVO(ticket);
    }

    @Override
    @Transactional
    public void createForAbnormal(String orderNo, Long applicantId, String reason) {
        // §7.15：标记异常自动生成 WORKER_MARK 工单；已存在则不重复建（唯一键也会拦）
        long exists = appealMapper.selectCount(new LambdaQueryWrapper<AppealTicket>()
                .eq(AppealTicket::getOrderNo, orderNo)
                .eq(AppealTicket::getAppealType, AppealType.WORKER_MARK.name()));
        if (exists > 0) {
            log.info("[appeal] 异常单已有 WORKER_MARK 工单，跳过重复建单 orderNo={}", orderNo);
            return;
        }
        ServiceOrder order = orderService.getByOrderNo(orderNo);
        AppealTicket ticket = new AppealTicket();
        ticket.setAppealNo(OrderNoGenerator.next("AP"));
        ticket.setOrderNo(orderNo);
        ticket.setApplicantId(applicantId);
        ticket.setApplicantRole(ROLE_SYSTEM);
        ticket.setAppealType(AppealType.WORKER_MARK.name());
        ticket.setReason(StringUtils.hasText(reason) ? reason.trim() : "陪诊师标记订单异常");
        ticket.setAmount(order == null ? null : order.getAmount());
        ticket.setStatus(STATUS_PENDING);
        ticket.setAutoConfirmPaused(0);
        ticket.setVersion(0);
        try {
            appealMapper.insert(ticket);
            log.info("[appeal] 异常单已自动建申诉工单 orderNo={} appealNo={}", orderNo, ticket.getAppealNo());
        } catch (DuplicateKeyException e) {
            log.info("[appeal] 异常单建单并发冲突，已忽略 orderNo={}", orderNo);
        }
    }

    /** 前置条件：orderStatus ∈ {40 待确认, 50 已完成, 70 异常}（契约 §5.14） */
    private void checkAppealableState(ServiceOrder order) {
        OrderState state = OrderState.ofNullable(order.getOrderStatus());
        if (state == null) {
            throw new BizException(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        boolean allowed = state == OrderState.PENDING_CONFIRM
                || state == OrderState.COMPLETED
                || state == OrderState.ABNORMAL;
        if (!allowed) {
            throw new BizException(ErrorCode.ORDER_STATE_ILLEGAL, "当前订单状态不支持申诉");
        }
    }

    private AppealVO toVO(AppealTicket ticket) {
        AppealVO vo = new AppealVO();
        vo.setAppealNo(ticket.getAppealNo());
        vo.setStatus(ticket.getStatus());
        vo.setType(ticket.getAppealType());
        AppealType type = AppealType.of(ticket.getAppealType());
        vo.setTypeName(type == null ? ticket.getAppealType() : type.getLabel());
        vo.setReason(ticket.getReason());
        vo.setEvidenceUrls(splitUrls(ticket.getEvidenceUrls()));
        vo.setArbitrateResult(ticket.getArbitrateResult());
        vo.setRefundAmount(ticket.getRefundAmount());
        vo.setAdminRemark(ticket.getAdminRemark());
        vo.setHandleTime(ticket.getHandleTime());
        vo.setCreatedAt(ticket.getCreatedAt());
        return vo;
    }

    private String joinUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return null;
        }
        List<String> cleaned = new ArrayList<>();
        for (String url : urls) {
            if (StringUtils.hasText(url)) {
                cleaned.add(url.trim());
            }
        }
        if (cleaned.isEmpty()) {
            return null;
        }
        // 超出上限直接截断，避免超长导致列溢出（列 1500，6 张 * 255 已足够）
        if (cleaned.size() > MAX_EVIDENCE) {
            cleaned = cleaned.subList(0, MAX_EVIDENCE);
        }
        return String.join(",", cleaned);
    }

    private List<String> splitUrls(String csv) {
        if (!StringUtils.hasText(csv)) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(StringUtils::hasText).toList();
    }

    /**
     * 找出这批订单中「已暂停自动确认」的订单号（阶段 5）。
     *
     * <p>一次性 in 查询，避免定时任务逐单查库形成 N+1。
     */
    @Override
    public Set<String> findAutoConfirmPausedOrderNos(Collection<String> orderNos) {
        if (orderNos == null || orderNos.isEmpty()) {
            return Set.of();
        }
        List<AppealTicket> tickets = appealMapper.selectList(new LambdaQueryWrapper<AppealTicket>()
                .select(AppealTicket::getOrderNo)
                .in(AppealTicket::getOrderNo, orderNos)
                .eq(AppealTicket::getAutoConfirmPaused, 1));
        return tickets.stream()
                .map(AppealTicket::getOrderNo)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }

    private String trimToNull(String text) {
        return StringUtils.hasText(text) ? text.trim() : null;
    }
}
