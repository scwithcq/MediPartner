package com.dsc.medipartner.module.demand.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.util.OrderNoGenerator;
import com.dsc.medipartner.module.demand.domain.entity.DemandOrder;
import com.dsc.medipartner.module.demand.domain.enums.DemandStatus;
import com.dsc.medipartner.module.demand.domain.vo.DemandVO;
import com.dsc.medipartner.module.demand.domain.vo.RecommendSnapshot;
import com.dsc.medipartner.module.demand.mapper.DemandOrderMapper;
import com.dsc.medipartner.module.demand.service.DemandService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 需求工单服务实现（接口文档 §4.3~§4.5）。
 * 状态机：OPEN -> ORDERED（转单）/ OPEN -> CLOSED（关闭，CLOSED 幂等，ORDERED 拒绝关闭）。
 */
@Slf4j
@Service
public class DemandServiceImpl implements DemandService {

    private final DemandOrderMapper demandOrderMapper;
    private final ObjectMapper objectMapper;

    public DemandServiceImpl(DemandOrderMapper demandOrderMapper, ObjectMapper objectMapper) {
        this.demandOrderMapper = demandOrderMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public DemandOrder createOpen(Long userId, String symptomDesc, String city,
                                  String hospitalPref, LocalDateTime serviceTime) {
        DemandOrder order = new DemandOrder();
        order.setDemandNo(OrderNoGenerator.next("DM"));
        order.setUserId(userId);
        order.setSymptomDesc(symptomDesc);
        order.setCity(city);
        order.setHospitalPref(hospitalPref);
        order.setServiceTime(serviceTime);
        order.setStatus(DemandStatus.OPEN.name());
        order.setRegenerateCount(0);
        demandOrderMapper.insert(order);
        return order;
    }

    @Override
    public DemandOrder findOwned(Long userId, Long demandId) {
        DemandOrder order = demandOrderMapper.selectById(demandId);
        if (order == null) {
            throw new BizException(ErrorCode.TICKET_NOT_FOUND);
        }
        if (!order.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return order;
    }

    @Override
    public void applyRecommendation(Long demandId, RecommendSnapshot snapshot) {
        String json;
        try {
            json = objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            log.error("[demand] 推荐快照序列化失败 demandId={}", demandId, e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "推荐结果保存失败");
        }
        DemandOrder update = new DemandOrder();
        update.setId(demandId);
        update.setAiRecommendJson(json);
        demandOrderMapper.updateById(update);
    }

    @Override
    public void incrementRegenerate(Long demandId) {
        DemandOrder order = demandOrderMapper.selectById(demandId);
        if (order == null) {
            throw new BizException(ErrorCode.TICKET_NOT_FOUND);
        }
        DemandOrder update = new DemandOrder();
        update.setId(demandId);
        update.setRegenerateCount(order.getRegenerateCount() == null ? 1 : order.getRegenerateCount() + 1);
        demandOrderMapper.updateById(update);
    }

    @Override
    @Transactional
    public void close(Long userId, Long demandId, String reason) {
        DemandOrder order = findOwned(userId, demandId);
        if (DemandStatus.CLOSED.name().equals(order.getStatus())) {
            return; // 幂等：已关闭直接成功
        }
        if (!DemandStatus.OPEN.name().equals(order.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATE_ILLEGAL, "工单已转订单，无法关闭");
        }
        DemandOrder update = new DemandOrder();
        update.setId(demandId);
        update.setStatus(DemandStatus.CLOSED.name());
        update.setCloseReason(reason);
        demandOrderMapper.updateById(update);
    }

    @Override
    @Transactional
    public void markOrdered(Long demandId, String orderNo) {
        DemandOrder order = demandOrderMapper.selectById(demandId);
        if (order == null) {
            throw new BizException(ErrorCode.TICKET_NOT_FOUND);
        }
        if (DemandStatus.ORDERED.name().equals(order.getStatus())) {
            return; // 幂等
        }
        if (!DemandStatus.OPEN.name().equals(order.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATE_ILLEGAL, "工单已关闭，无法转订单");
        }
        DemandOrder update = new DemandOrder();
        update.setId(demandId);
        update.setStatus(DemandStatus.ORDERED.name());
        update.setOrderNo(orderNo);
        demandOrderMapper.updateById(update);
    }

    @Override
    public IPage<DemandVO> pageMine(Long userId, String status, LocalDateTime updatedSince,
                                    long pageNum, long pageSize) {
        if (StringUtils.hasText(status) && parseStatus(status) == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "非法的工单状态: " + status);
        }
        LambdaQueryWrapper<DemandOrder> wrapper = new LambdaQueryWrapper<DemandOrder>()
                .eq(DemandOrder::getUserId, userId)
                .eq(StringUtils.hasText(status), DemandOrder::getStatus, status)
                .ge(updatedSince != null, DemandOrder::getUpdatedAt, updatedSince)
                .orderByDesc(DemandOrder::getCreatedAt);
        Page<DemandOrder> page = demandOrderMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return page.convert(this::toListVO);
    }

    @Override
    public DemandVO detail(Long userId, Long demandId) {
        DemandOrder order = findOwned(userId, demandId);
        DemandVO vo = toListVO(order);
        RecommendSnapshot snapshot = parseSnapshot(order);
        if (snapshot != null) {
            vo.setDepartments(snapshot.getDepartments());
        }
        return vo;
    }

    private DemandVO toListVO(DemandOrder order) {
        DemandVO vo = new DemandVO();
        vo.setDemandId(String.valueOf(order.getId()));
        vo.setDemandNo(order.getDemandNo());
        vo.setSymptomDesc(order.getSymptomDesc());
        vo.setCity(order.getCity());
        vo.setHospitalPref(order.getHospitalPref());
        vo.setServiceTime(order.getServiceTime());
        vo.setStatus(order.getStatus());
        vo.setOrderNo(order.getOrderNo());
        vo.setCreatedAt(order.getCreatedAt());
        vo.setUpdatedAt(order.getUpdatedAt());
        RecommendSnapshot snapshot = parseSnapshot(order);
        if (snapshot != null && snapshot.getDepartments() != null && !snapshot.getDepartments().isEmpty()) {
            vo.setTopDepartment(snapshot.getDepartments().get(0).getDepartmentName());
        }
        return vo;
    }

    private RecommendSnapshot parseSnapshot(DemandOrder order) {
        if (!StringUtils.hasText(order.getAiRecommendJson())) {
            return null;
        }
        try {
            return objectMapper.readValue(order.getAiRecommendJson(), RecommendSnapshot.class);
        } catch (Exception e) {
            log.warn("[demand] 推荐快照解析失败 demandId={}: {}", order.getId(), e.getMessage());
            return null;
        }
    }

    private DemandStatus parseStatus(String status) {
        try {
            return DemandStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
