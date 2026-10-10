-- 阶段 3：service_order 补列（契约附录 C：demand_id 关联需求工单；服务小结/实际时长供 AI 报告与风控）
-- virtual_phone / price_version / refunded_amount / abnormal_* 属阶段 1.5/4 契约，随对应阶段迁移再补
ALTER TABLE service_order
    ADD COLUMN demand_id       BIGINT       DEFAULT NULL COMMENT '关联需求工单ID(demand_order.id)' AFTER worker_id,
    ADD COLUMN service_summary VARCHAR(500) DEFAULT NULL COMMENT '陪诊师服务小结(finish 提交, AI 报告素材)',
    ADD COLUMN actual_hours    DECIMAL(4, 1) DEFAULT NULL COMMENT '实际服务时长(小时, 0.5~24, 一位小数)',
    ADD KEY idx_demand_id (demand_id);
