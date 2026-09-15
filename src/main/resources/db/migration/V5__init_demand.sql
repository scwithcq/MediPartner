-- 需求工单表：AI 发单助手产出的「需求 -> AI 推荐 -> 转订单」载体
-- 阶段 0 修复时按决策提前建表（demand_order 补建），模块代码在阶段 2 落地
CREATE TABLE demand_order (
    id                BIGINT       NOT NULL COMMENT '主键',
    demand_no         VARCHAR(32)  NOT NULL COMMENT '需求单号(DM+yyyyMMddHHmmss+6位随机)',
    user_id           BIGINT       NOT NULL COMMENT '求助者用户ID',
    symptom_desc      VARCHAR(500) NOT NULL COMMENT '症状描述',
    city              VARCHAR(64)  NOT NULL COMMENT '期望就医城市',
    hospital_pref     VARCHAR(255) DEFAULT NULL COMMENT '医院偏好',
    service_time      DATETIME     DEFAULT NULL COMMENT '期望陪诊时间',
    ai_recommend_json JSON         DEFAULT NULL COMMENT 'AI推荐科室与引用来源快照',
    status            VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT '状态: OPEN待下单 ORDERED已转订单 CLOSED已关闭',
    order_no          VARCHAR(32)  DEFAULT NULL COMMENT '转化后的订单号',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_demand_no (demand_no),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '需求工单表';
