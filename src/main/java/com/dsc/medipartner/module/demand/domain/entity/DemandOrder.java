package com.dsc.medipartner.module.demand.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsc.medipartner.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 需求工单（V5 建表 + V8.1 补列）：AI 发单助手产出的「需求 -> AI 推荐 -> 转订单」载体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("demand_order")
public class DemandOrder extends BaseEntity {

    /** DM + yyyyMMddHHmmss + 6 位随机 */
    private String demandNo;
    private Long userId;
    private String symptomDesc;
    private String city;
    private String hospitalPref;
    private LocalDateTime serviceTime;
    /** AI 推荐快照 JSON（RecommendSnapshot 序列化结果） */
    private String aiRecommendJson;
    /** DemandStatus.name(): OPEN / ORDERED / CLOSED */
    private String status;
    private String orderNo;
    /** 重新生成次数（上限 medi.ai.max-regenerate） */
    private Integer regenerateCount;
    private String closeReason;
}
