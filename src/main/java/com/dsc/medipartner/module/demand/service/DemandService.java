package com.dsc.medipartner.module.demand.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.module.demand.domain.entity.DemandOrder;
import com.dsc.medipartner.module.demand.domain.vo.DemandVO;
import com.dsc.medipartner.module.demand.domain.vo.RecommendSnapshot;

import java.time.LocalDateTime;

/**
 * 需求工单服务（接口文档 4.3~4.5）。
 * markOrdered 预留给阶段 1.5：下单携带 demandId 时把工单 OPEN -> ORDERED。
 */
public interface DemandService {

    /** 创建 OPEN 状态工单（AI 发单助手调用） */
    DemandOrder createOpen(Long userId, String symptomDesc, String city, String hospitalPref, LocalDateTime serviceTime);

    /** 按 ID 查询并校验归属：不存在 5001，非本人 1003 */
    DemandOrder findOwned(Long userId, Long demandId);

    /** 写入/覆盖 AI 推荐快照 */
    void applyRecommendation(Long demandId, RecommendSnapshot snapshot);

    /** 重新生成次数 +1 */
    void incrementRegenerate(Long demandId);

    /** 关闭工单：仅 OPEN -> CLOSED（CLOSED 幂等成功），ORDERED 抛 3002 */
    void close(Long userId, Long demandId, String reason);

    /** 转订单：OPEN -> ORDERED 并记录订单号（阶段 1.5 接入） */
    void markOrdered(Long demandId, String orderNo);

    /** 我的工单分页（列表 VO，topDepartment 取自快照） */
    IPage<DemandVO> pageMine(Long userId, String status, LocalDateTime updatedSince, long pageNum, long pageSize);

    /** 工单详情（含完整 departments 快照） */
    DemandVO detail(Long userId, Long demandId);
}
