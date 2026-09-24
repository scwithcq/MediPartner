-- 需求工单补列：重新生成计数（§4.2 上限 5 次）与关闭原因（§4.5）
ALTER TABLE demand_order
    ADD COLUMN regenerate_count INT          NOT NULL DEFAULT 0 COMMENT 'AI推荐重新生成次数, 上限5次' AFTER order_no,
    ADD COLUMN close_reason     VARCHAR(100) DEFAULT NULL COMMENT '关闭原因' AFTER regenerate_count;
