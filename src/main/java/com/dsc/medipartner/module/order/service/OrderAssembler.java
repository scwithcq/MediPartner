package com.dsc.medipartner.module.order.service;

import com.dsc.medipartner.common.util.DesensitizeUtil;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.domain.enums.ServiceType;
import com.dsc.medipartner.module.order.domain.vo.OrderCardVO;
import com.dsc.medipartner.module.order.domain.vo.OrderDetailVO;
import com.dsc.medipartner.module.order.domain.vo.PriceDetailVO;
import com.dsc.medipartner.module.order.domain.vo.WorkerBriefVO;
import com.dsc.medipartner.module.user.domain.entity.SysUser;
import com.dsc.medipartner.module.user.domain.entity.WorkerProfile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 订单实体 → VO 组装器（契约 §5.3、§5.4、附录 H-4）。
 *
 * <p>把「实体不出 Controller」这条约束落在一处：所有对外订单出参都经本类组装，
 * 杜绝 {@code version} / {@code userId} / {@code workerId} 等内部字段外泄。
 */
@Component
public class OrderAssembler {

    /** 列表页症状摘要截断长度（§5.3） */
    private static final int SYMPTOM_BRIEF_MAX = 50;

    /**
     * 组装列表卡片。
     *
     * @param order   订单实体
     * @param worker  接单陪诊师资料，未接单传 null（该键将缺失）
     * @param actions 由 ActionResolver 算出的动作码
     */
    public OrderCardVO toCard(ServiceOrder order, WorkerProfile worker, SysUser workerUser,
                              List<String> actions) {
        OrderCardVO vo = new OrderCardVO();
        fillCommon(vo, order, actions);
        if (worker != null || workerUser != null) {
            vo.setWorker(toWorkerBrief(worker, workerUser, false));
        }
        return vo;
    }

    /**
     * 组装详情（§5.4）。
     *
     * @param worker   接单陪诊师资料，未接单传 null
     * @param isOwnerPeerDetail 是否下发对方完整摘要（详情页含 realName/creditScore 等）
     */
    public OrderDetailVO toDetail(ServiceOrder order, WorkerProfile worker, SysUser workerUser,
                                  List<String> actions) {
        OrderDetailVO vo = new OrderDetailVO();
        fillCommon(vo, order, actions);
        vo.setOrderId(order.getId());
        vo.setSymptomDesc(order.getSymptomDesc());
        // §1.11：联系电话一律脱敏后返回
        vo.setContactPhone(DesensitizeUtil.phone(order.getContactPhone()));
        vo.setGeoHash(order.getGeoHash());
        vo.setDemandId(order.getDemandId());
        vo.setCancelReason(order.getCancelReason());
        vo.setRefundAmount(order.getRefundedAmount());
        vo.setAcceptedAt(order.getAcceptedAt());
        vo.setStartedAt(order.getStartedAt());
        vo.setFinishedAt(order.getFinishedAt());
        vo.setConfirmedAt(order.getConfirmedAt());
        vo.setPriceDetail(toPriceDetail(order));
        if (worker != null || workerUser != null) {
            vo.setWorker(toWorkerBrief(worker, workerUser, true));
        }
        return vo;
    }

    /** 卡片与详情共有的字段 */
    private void fillCommon(OrderCardVO vo, ServiceOrder order, List<String> actions) {
        vo.setOrderNo(order.getOrderNo());
        // 附录 B.1：状态下发枚举名字符串，而非 int code
        OrderState state = OrderState.ofNullable(order.getOrderStatus());
        vo.setOrderStatus(state == null ? null : state.name());
        vo.setOrderStatusName(OrderState.nameOf(order.getOrderStatus()));
        vo.setServiceType(order.getServiceType());
        vo.setServiceTypeName(serviceTypeLabel(order.getServiceType()));
        vo.setSymptomBrief(brief(order.getSymptomDesc()));
        vo.setCity(order.getCity());
        vo.setHospitalPref(order.getHospitalPref());
        vo.setServiceTime(order.getServiceTime());
        vo.setAmount(order.getAmount());
        vo.setActions(actions);
        vo.setCreatedAt(order.getCreatedAt());
        vo.setUpdatedAt(order.getUpdatedAt());
    }

    /** 由订单自身的计价快照还原计价明细（§5.4） */
    public PriceDetailVO toPriceDetail(ServiceOrder order) {
        if (order.getAmount() == null) {
            return null;
        }
        PriceDetailVO detail = new PriceDetailVO();
        detail.setServiceType(order.getServiceType());
        detail.setServiceTypeName(serviceTypeLabel(order.getServiceType()));
        detail.setHours(order.getServiceHours());
        detail.setAmount(order.getAmount());
        detail.setPriceRuleId(order.getPriceRuleId());
        detail.setPriceVersion(order.getPriceVersion());
        return detail;
    }

    /**
     * 陪诊师摘要。
     *
     * @param withDetail true 时补 realName / creditScore / goodRate / intro（详情页用）
     */
    private WorkerBriefVO toWorkerBrief(WorkerProfile worker, SysUser user, boolean withDetail) {
        WorkerBriefVO brief = new WorkerBriefVO();
        if (worker != null) {
            brief.setWorkerUserId(worker.getUserId());
        } else if (user != null) {
            brief.setWorkerUserId(user.getId());
        }
        if (user != null) {
            brief.setNickname(user.getNickname());
            brief.setAvatar(user.getAvatar());
            // §1.11：对方手机号脱敏
            brief.setMaskedPhone(DesensitizeUtil.phone(user.getPhone()));
        }
        if (worker != null) {
            brief.setRealName(DesensitizeUtil.realName(worker.getRealName()));
            if (withDetail) {
                brief.setCreditScore(worker.getCreditScore());
                brief.setGoodRate(worker.getGoodRate());
                brief.setServiceCount(worker.getServiceCount());
                brief.setIntro(worker.getIntro());
            }
        }
        return brief;
    }

    /** 截断至 50 字并加省略号（§5.3 symptomBrief） */
    private String brief(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        String trimmed = text.trim();
        return trimmed.length() <= SYMPTOM_BRIEF_MAX
                ? trimmed
                : trimmed.substring(0, SYMPTOM_BRIEF_MAX) + "…";
    }

    /** 服务类型中文名（附录 B.6）；未知类型原样返回码值 */
    private String serviceTypeLabel(String serviceType) {
        if (!StringUtils.hasText(serviceType)) {
            return serviceType;
        }
        try {
            return ServiceType.valueOf(serviceType).getLabel();
        } catch (IllegalArgumentException e) {
            return serviceType;
        }
    }
}
