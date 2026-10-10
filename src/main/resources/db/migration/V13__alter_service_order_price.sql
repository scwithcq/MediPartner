-- 阶段 1.5：service_order 补列（契约附录 C / H-10）
-- V9 已补 demand_id / service_summary / actual_hours（阶段 3）；
-- 本脚本补阶段 1.5 服务端计价与下单契约（§5.2）所需列。
-- virtual_phone / refunded_amount / abnormal_type / abnormal_remark 属阶段 4 契约，随阶段 4 迁移再补。
ALTER TABLE service_order
    ADD COLUMN price_version  VARCHAR(32)    DEFAULT NULL COMMENT '下单时锁定的定价版本号(来自 service_price_rule.price_version)' AFTER amount,
    ADD COLUMN price_rule_id  BIGINT         DEFAULT NULL COMMENT '命中的定价规则ID(service_price_rule.id)' AFTER price_version,
    ADD COLUMN service_hours  DECIMAL(4, 1)  DEFAULT NULL COMMENT '预计服务时长(小时, 用户下单填写, 参与计价)' AFTER service_type,
    ADD COLUMN contact_phone  VARCHAR(16)    DEFAULT NULL COMMENT '联系电话(不传则用账号绑定手机号)' AFTER city,
    ADD COLUMN remark         VARCHAR(255)   DEFAULT NULL COMMENT '下单补充说明';
