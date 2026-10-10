-- 阶段 1.5：服务端定价表（契约 §5.1、附录 B.6、附录 D/H-1）
-- 约束遵循附录 D：无 AUTO_INCREMENT（雪花 ID）、无逻辑删除、utf8mb4_0900_ai_ci、created_at/updated_at 默认值
-- 金额列一律 DECIMAL(12,2)，枚举名 VARCHAR(32)，状态码 INT
CREATE TABLE service_price_rule (
    id               BIGINT         NOT NULL COMMENT '主键',
    city_code        VARCHAR(16)    NOT NULL COMMENT '城市国标6位行政区划码, DEFAULT=全国默认规则',
    city_name        VARCHAR(64)    DEFAULT NULL COMMENT '城市中文名(仅供展示)',
    service_type     VARCHAR(32)    NOT NULL COMMENT '服务类型: ACCOMPANY/HALF_DAY/FULL_DAY/PICKUP_REPORT/CONSULT_AGENT',
    base_price       DECIMAL(12, 2) NOT NULL COMMENT '起步价(含 base_hours)',
    base_hours       DECIMAL(4, 1)  NOT NULL DEFAULT 1.0 COMMENT '起步价含时长(小时)',
    extra_hour_price DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '加时单价(每小时), 一口价服务为 0',
    max_hours        DECIMAL(4, 1)  NOT NULL DEFAULT 24.0 COMMENT '单笔最长服务时长(小时)',
    min_hours        DECIMAL(4, 1)  NOT NULL DEFAULT 0.5 COMMENT '最短服务时长(小时)',
    platform_rate    DECIMAL(6, 4)  NOT NULL DEFAULT 0.0000 COMMENT '平台抽成比例(本项目不抽成, 预留)',
    price_version    VARCHAR(32)    NOT NULL DEFAULT '1' COMMENT '定价版本号, 调价时递增, 用于下单一致性校验(3010)',
    enabled          TINYINT        NOT NULL DEFAULT 1 COMMENT '是否启用: 1启用 0停用(停用返回3009)',
    remark           VARCHAR(255)   DEFAULT NULL COMMENT '计价说明文案, 由 §5.1 下发前端直接展示',
    effective_at     DATETIME       DEFAULT NULL COMMENT '定时生效时间, NULL=立即生效',
    created_at       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_city_service (city_code, service_type),
    KEY idx_service_type (service_type, enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '服务定价规则表';

-- 定价种子数据（契约 §11 Q2 默认方案）。
-- ⚠ 待业务方最终拍板的项：HALF_DAY 加时单价（Q2 取 25.00, §5.1 备注3 取 30.00, 此处按 Q2）、
--   HALF_DAY 起步含时长（§5.1/Q2 为 3h, 附录 B.6 为 4h, 此处按 §5.1/Q2 的 3h）；
--   PICKUP_REPORT / CONSULT_AGENT 按附录 B.6「一口价」实现（extra_hour_price=0，不采用 Q2 的 20/h、40/h）。
-- city_code 暂用 'DEFAULT' 全国默认规则；Q1 城市确定后追加该城市专属行即可，无需改代码。
INSERT INTO service_price_rule
    (id, city_code, city_name, service_type, base_price, base_hours, extra_hour_price, max_hours, min_hours, price_version, enabled, remark)
VALUES
    (40001, 'DEFAULT', NULL, 'ACCOMPANY',      200.00, 4.0, 30.00, 24.0, 0.5, '1', 1, '全程陪诊 200.00 元含 4 小时，超出部分 30.00 元/小时，不足 0.5 小时按 0.5 小时计'),
    (40002, 'DEFAULT', NULL, 'HALF_DAY',       150.00, 3.0, 25.00, 24.0, 0.5, '1', 1, '半日陪护 150.00 元含 3 小时，超出部分 25.00 元/小时，不足 0.5 小时按 0.5 小时计'),
    (40003, 'DEFAULT', NULL, 'FULL_DAY',       280.00, 8.0, 30.00, 24.0, 0.5, '1', 1, '全日陪护 280.00 元含 8 小时，超出部分 30.00 元/小时，不足 0.5 小时按 0.5 小时计'),
    (40004, 'DEFAULT', NULL, 'PICKUP_REPORT',   60.00, 1.0,  0.00, 24.0, 0.5, '1', 1, '代取报告 60.00 元一口价（含 1 小时），不含陪同就诊'),
    (40005, 'DEFAULT', NULL, 'CONSULT_AGENT',  120.00, 2.0,  0.00, 24.0, 0.5, '1', 1, '代问诊 120.00 元一口价（含 2 小时），陪诊师代患者面诊并回传结论');
