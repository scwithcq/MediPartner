package com.dsc.medipartner.module.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsc.medipartner.module.order.domain.entity.OrderStatusLog;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.domain.vo.OrderTimelineNodeVO;
import com.dsc.medipartner.module.order.mapper.OrderStatusLogMapper;
import com.dsc.medipartner.module.order.service.OrderStatusLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 订单流转日志实现（契约 §5.5）。
 *
 * <p>读取时若发现该订单<b>没有任何日志</b>（阶段 1 存量订单），按订单上的各节点时间
 * 反推补齐，避免时间轴空白（§5.5 备注 3）。
 */
@Slf4j
@Service
public class OrderStatusLogServiceImpl implements OrderStatusLogService {

    /** 操作方（附录 B.10） */
    public static final String OPERATOR_USER = "USER";
    public static final String OPERATOR_WORKER = "WORKER";
    public static final String OPERATOR_ADMIN = "ADMIN";
    public static final String OPERATOR_SYSTEM = "SYSTEM";

    private static final String SYSTEM_NAME = "系统";
    private static final String BACKFILL_REMARK = "历史数据补全";

    private final OrderStatusLogMapper logMapper;

    public OrderStatusLogServiceImpl(OrderStatusLogMapper logMapper) {
        this.logMapper = logMapper;
    }

    @Override
    public void record(String orderNo, Integer fromState, Integer toState, String event,
                       String operatorType, Long operatorId, String remark) {
        OrderStatusLog entry = new OrderStatusLog();
        entry.setOrderNo(orderNo);
        entry.setFromState(fromState);
        entry.setToState(toState);
        entry.setEvent(event);
        entry.setOperatorType(operatorType == null ? OPERATOR_SYSTEM : operatorType);
        entry.setOperatorId(operatorId);
        entry.setOperatorName(resolveOperatorName(entry.getOperatorType(), operatorId));
        entry.setTitle(titleOf(toState, event));
        entry.setRemark(remark);
        logMapper.insert(entry);
    }

    @Override
    public void recordAction(String orderNo, String event, String operatorType, Long operatorId,
                            String title, String remark) {
        OrderStatusLog entry = new OrderStatusLog();
        entry.setOrderNo(orderNo);
        // 不改变状态的动作：from/to 都留空，只记事件，避免污染状态机轨迹（附录 A 末尾说明）
        entry.setEvent(event);
        entry.setOperatorType(operatorType == null ? OPERATOR_SYSTEM : operatorType);
        entry.setOperatorId(operatorId);
        entry.setOperatorName(resolveOperatorName(entry.getOperatorType(), operatorId));
        // 标题优先用调用方给的文案；没给则按事件推导友好中文（避免把 WORKER_REPLY 这类码值直接展示）
        entry.setTitle(StringUtils.hasText(title) ? title : titleOf(null, event));
        entry.setRemark(remark);
        logMapper.insert(entry);
    }

    @Override
    public List<OrderTimelineNodeVO> timeline(ServiceOrder order) {
        List<OrderStatusLog> logs = loadLogs(order.getOrderNo());
        if (logs.isEmpty()) {
            // 阶段 1 存量订单无日志 -> 按各节点时间反推补齐（§5.5 备注 3）
            return backfill(order);
        }
        List<OrderTimelineNodeVO> nodes = new ArrayList<>(logs.size());
        for (OrderStatusLog entry : logs) {
            nodes.add(toNode(entry));
        }
        nodes.sort(Comparator.comparing(OrderTimelineNodeVO::getOccurredAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return nodes;
    }

    @Override
    public List<OrderTimelineNodeVO> recentTimeline(ServiceOrder order, int limit) {
        List<OrderTimelineNodeVO> all = timeline(order);
        if (all.size() <= limit) {
            return all;
        }
        // 取最近 limit 条，但仍按时间升序返回（§5.4 要求与 §5.5 同序）
        return new ArrayList<>(all.subList(all.size() - limit, all.size()));
    }

    private List<OrderStatusLog> loadLogs(String orderNo) {
        return logMapper.selectList(new LambdaQueryWrapper<OrderStatusLog>()
                .eq(OrderStatusLog::getOrderNo, orderNo)
                .orderByAsc(OrderStatusLog::getCreatedAt)
                .orderByAsc(OrderStatusLog::getId));
    }

    private OrderTimelineNodeVO toNode(OrderStatusLog entry) {
        OrderTimelineNodeVO node = new OrderTimelineNodeVO();
        node.setStatus(entry.getToState());
        node.setStatusName(OrderState.nameOf(entry.getToState()));
        node.setEvent(entry.getEvent());
        node.setTitle(StringUtils.hasText(entry.getTitle())
                ? entry.getTitle() : titleOf(entry.getToState(), entry.getEvent()));
        node.setRemark(entry.getRemark());
        node.setOperatorType(entry.getOperatorType());
        node.setOperatorName(entry.getOperatorName());
        node.setOccurredAt(entry.getCreatedAt());
        return node;
    }

    /**
     * 存量订单反推：按订单上已有的时间戳还原节点，remark 统一标注「历史数据补全」，
     * 让前端与仲裁人员知道这些节点是推导出来的、不是实时记录的。
     */
    private List<OrderTimelineNodeVO> backfill(ServiceOrder order) {
        List<OrderTimelineNodeVO> nodes = new ArrayList<>();
        // 下单：createdAt 必有
        addNode(nodes, OrderState.WAITING_ACCEPT, "CREATE", OPERATOR_USER,
                order.getUserId(), order.getCreatedAt(), BACKFILL_REMARK);
        if (order.getWorkerId() != null && order.getAcceptedAt() != null) {
            addNode(nodes, OrderState.ACCEPTED, "ACCEPT", OPERATOR_WORKER,
                    order.getWorkerId(), order.getAcceptedAt(), BACKFILL_REMARK);
        }
        if (order.getStartedAt() != null) {
            addNode(nodes, OrderState.IN_SERVICE, "START", OPERATOR_WORKER,
                    order.getWorkerId(), order.getStartedAt(), BACKFILL_REMARK);
        }
        if (order.getFinishedAt() != null) {
            addNode(nodes, OrderState.PENDING_CONFIRM, "FINISH", OPERATOR_WORKER,
                    order.getWorkerId(), order.getFinishedAt(), BACKFILL_REMARK);
        }
        if (order.getConfirmedAt() != null) {
            addNode(nodes, OrderState.COMPLETED, "USER_CONFIRM", OPERATOR_USER,
                    order.getUserId(), order.getConfirmedAt(), BACKFILL_REMARK);
        }
        // 终态但无对应时间戳时，至少补一个当前状态的节点，避免时间轴整个空白
        OrderState current = OrderState.ofNullable(order.getOrderStatus());
        if (current != null && nodes.stream().noneMatch(n -> current.getCode() == n.getStatus())) {
            addNode(nodes, current, null, OPERATOR_SYSTEM, null,
                    order.getUpdatedAt() != null ? order.getUpdatedAt() : order.getCreatedAt(),
                    BACKFILL_REMARK);
        }
        nodes.sort(Comparator.comparing(OrderTimelineNodeVO::getOccurredAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return nodes;
    }

    private void addNode(List<OrderTimelineNodeVO> nodes, OrderState state, String event,
                         String operatorType, Long operatorId, LocalDateTime occurredAt, String remark) {
        if (occurredAt == null) {
            return;
        }
        OrderTimelineNodeVO node = new OrderTimelineNodeVO();
        node.setStatus(state.getCode());
        node.setStatusName(state.getStateName());
        node.setEvent(event);
        node.setTitle(titleOf(state.getCode(), event));
        node.setRemark(remark);
        node.setOperatorType(operatorType);
        node.setOperatorName(resolveOperatorName(operatorType, operatorId));
        node.setOccurredAt(occurredAt);
        nodes.add(node);
    }

    /** 操作人展示名：SYSTEM 固定「系统」，其余按 ID 生成脱敏称呼 */
    private String resolveOperatorName(String operatorType, Long operatorId) {
        if (OPERATOR_SYSTEM.equals(operatorType) || operatorId == null) {
            return SYSTEM_NAME;
        }
        return switch (operatorType) {
            case OPERATOR_USER -> "求助者";
            case OPERATOR_WORKER -> "陪诊师";
            case OPERATOR_ADMIN -> "平台管理员";
            default -> SYSTEM_NAME;
        };
    }

    /** 状态 + 事件 -> 展示标题（§5.5 title 字段）；事件优先，状态作兜底 */
    private String titleOf(Integer toState, String event) {
        if (StringUtils.hasText(event)) {
            String byEvent = titleOfEvent(event.trim());
            if (byEvent != null) {
                return byEvent;
            }
        }
        OrderState state = OrderState.ofNullable(toState);
        if (state == null) {
            return StringUtils.hasText(event) ? event : "";
        }
        return switch (state) {
            case WAITING_ACCEPT -> "订单已创建，等待接单";
            case ACCEPTED -> "陪诊师已接单";
            case IN_SERVICE -> "陪诊师已开始服务";
            case PENDING_CONFIRM -> "服务已结束，待确认";
            case COMPLETED -> "订单已完成";
            case CANCELLED -> "订单已取消";
            case ABNORMAL -> "订单已标记异常";
        };
    }

    /** 事件 -> 中文标题；未登记的事件返回 null 由调用方兜底 */
    private String titleOfEvent(String event) {
        return switch (event) {
            case "CREATE" -> "订单已创建，等待接单";
            case "ACCEPT" -> "陪诊师已接单";
            case "START" -> "陪诊师已开始服务";
            case "FINISH" -> "服务已结束，待确认";
            case "USER_CONFIRM" -> "用户已确认完成";
            case "AUTO_CONFIRM" -> "超时自动确认完成";
            case "USER_CANCEL" -> "用户已取消订单";
            case "TIMEOUT_CANCEL" -> "超时无人接单，订单已取消";
            case "MARK_ABNORMAL" -> "订单已标记异常";
            case "ADMIN_CANCEL" -> "管理员已取消订单";
            case "ADMIN_REFUND" -> "管理员已退款";
            case "WORKER_REPLY" -> "陪诊师已回复评价";
            case "APPEAL" -> "用户发起申诉";
            case "UPLOAD_TRACE" -> "上传服务留痕";
            default -> null;
        };
    }
}
