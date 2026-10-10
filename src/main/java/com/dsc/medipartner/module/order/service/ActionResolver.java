package com.dsc.medipartner.module.order.service;

import com.dsc.medipartner.module.order.domain.enums.OrderState;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端计算可执行操作 {@code actions[]}（契约 §1.5、§5.3、§7.7）。
 *
 * <p><b>为什么必须在服务端算</b>：前端不得用「状态 → 按钮」的 if-else 自己推。状态机新增流转
 * 或调整规则时，前端零改动；且权限判定永远由后端完成，前端只做展示（§1.5）。
 *
 * <p>返回的动作码是<b>稳定的对外契约</b>，前端遇到不认识的动作码应忽略而不是报错。
 *
 * <p>动作字典：
 * <ul>
 *   <li>求助者视角（§5.3）：{@code CANCEL / CONFIRM / CONTACT / VIEW_TRACE / VIEW_REPORT /
 *       REVIEW / APPEND_REVIEW / APPEAL / REORDER}</li>
 *   <li>陪诊师视角（§7.7）：{@code START / UPLOAD_TRACE / FINISH / MARK_ABNORMAL / CONTACT /
 *       VIEW_TIMELINE / REPLY_REVIEW}</li>
 * </ul>
 */
@Component
public class ActionResolver {

    // ---- 求助者视角动作码（§5.3）----
    public static final String CANCEL = "CANCEL";
    public static final String CONFIRM = "CONFIRM";
    public static final String CONTACT = "CONTACT";
    public static final String VIEW_TRACE = "VIEW_TRACE";
    public static final String VIEW_REPORT = "VIEW_REPORT";
    public static final String REVIEW = "REVIEW";
    public static final String APPEND_REVIEW = "APPEND_REVIEW";
    public static final String APPEAL = "APPEAL";
    public static final String REORDER = "REORDER";

    // ---- 陪诊师视角动作码（§7.7）----
    public static final String START = "START";
    public static final String UPLOAD_TRACE = "UPLOAD_TRACE";
    public static final String FINISH = "FINISH";
    public static final String MARK_ABNORMAL = "MARK_ABNORMAL";
    public static final String VIEW_TIMELINE = "VIEW_TIMELINE";
    public static final String REPLY_REVIEW = "REPLY_REVIEW";

    /**
     * 求助者视角（§5.3）。
     *
     * @param orderStatus 订单状态码
     * @param hasReview   是否已评价过（已评价则给追评而不是评价）
     * @return 按契约顺序排列的动作码；终态且无可做之事时返回空列表
     */
    public List<String> resolveForUser(Integer orderStatus, boolean hasReview) {
        List<String> actions = new ArrayList<>();
        OrderState state = safeState(orderStatus);
        if (state == null) {
            return actions;
        }
        switch (state) {
            case WAITING_ACCEPT -> {
                // 仅待接单可用户自助取消（附录 A：20 之后不能自助取消）
                actions.add(CANCEL);
                actions.add(REORDER);
            }
            case ACCEPTED -> {
                actions.add(CONTACT);
                actions.add(VIEW_TRACE);
            }
            case IN_SERVICE -> {
                actions.add(CONTACT);
                actions.add(VIEW_TRACE);
            }
            case PENDING_CONFIRM -> {
                actions.add(CONFIRM);
                actions.add(CONTACT);
                actions.add(VIEW_TRACE);
                actions.add(VIEW_REPORT);
                actions.add(APPEAL);
            }
            case COMPLETED -> {
                actions.add(VIEW_REPORT);
                if (hasReview) {
                    // 一评一追：已有评价则只能追评（§5.13）
                    actions.add(APPEND_REVIEW);
                } else {
                    actions.add(REVIEW);
                }
                actions.add(APPEAL);
                actions.add(REORDER);
            }
            case CANCELLED -> actions.add(REORDER);
            case ABNORMAL -> {
                // 异常单双方都没有自助出口，资金保持冻结等管理端处置（§7.15、D9）
                actions.add(CONTACT);
                actions.add(VIEW_TRACE);
                actions.add(APPEAL);
            }
            default -> {
            }
        }
        return actions;
    }

    /**
     * 陪诊师视角（§7.7）。
     *
     * @param orderStatus  订单状态码
     * @param hasReplied   是否已回复过该单评价（§7.21 一评价一回复）
     */
    public List<String> resolveForWorker(Integer orderStatus, boolean hasReplied) {
        List<String> actions = new ArrayList<>();
        OrderState state = safeState(orderStatus);
        if (state == null) {
            return actions;
        }
        switch (state) {
            case ACCEPTED -> {
                actions.add(START);
                actions.add(CONTACT);
                actions.add(VIEW_TIMELINE);
            }
            case IN_SERVICE -> {
                actions.add(UPLOAD_TRACE);
                actions.add(FINISH);
                actions.add(MARK_ABNORMAL);
                actions.add(CONTACT);
                actions.add(VIEW_TIMELINE);
            }
            case PENDING_CONFIRM -> {
                actions.add(CONTACT);
                actions.add(VIEW_TIMELINE);
            }
            case COMPLETED -> {
                actions.add(VIEW_TIMELINE);
                if (!hasReplied) {
                    actions.add(REPLY_REVIEW);
                }
            }
            case CANCELLED, ABNORMAL -> actions.add(VIEW_TIMELINE);
            default -> {
            }
        }
        return actions;
    }

    /** 状态码非法（库中脏数据）时返回 null，调用方给出空动作集而不是抛异常 */
    private OrderState safeState(Integer orderStatus) {
        if (orderStatus == null) {
            return null;
        }
        for (OrderState state : OrderState.values()) {
            if (state.getCode() == orderStatus) {
                return state;
            }
        }
        return null;
    }
}
