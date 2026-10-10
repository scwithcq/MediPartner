-- 阶段 1.5：建表补列（契约附录 H-10 / H-12 / H-13）
-- 前置：V11 已建 service_price_rule（计价）；V12 已给 wallet_transaction 加 biz_no；V13 已给 service_order 补计价列。
-- 本脚本补齐列表接口与钱包展示所需的列。
-- 约束遵循附录 D：无 AUTO_INCREMENT、无逻辑删除、utf8mb4_0900_ai_ci、金额 DECIMAL(12,2)。

-- ---------------------------------------------------------------
-- H-10 + H-12：service_order 补城市码与售后/异常相关列
-- H-12 决策：计价与运力匹配一律按 city_code；city 保留为冗余中文名仅供展示
-- virtual_phone / refunded_amount / abnormal_type / abnormal_remark / cancel_reason
--   属阶段 4（申诉仲裁、管理端干预）契约，本脚本一并补列，避免阶段 4 再改表
-- ---------------------------------------------------------------
ALTER TABLE service_order
    ADD COLUMN city_code       VARCHAR(16)    DEFAULT NULL COMMENT '就医城市国标6位码(计价与匹配口径, H-12)' AFTER city,
    ADD COLUMN cancel_reason   VARCHAR(255)   DEFAULT NULL COMMENT '取消原因(状态60/70时有值)' AFTER remark,
    ADD COLUMN virtual_phone   VARCHAR(32)    DEFAULT NULL COMMENT '虚拟号(由orderNo哈希稳定生成, 阶段4启用)' AFTER cancel_reason,
    ADD COLUMN refunded_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '累计已退款金额(仲裁/人工退款)' AFTER virtual_phone,
    ADD COLUMN abnormal_type   VARCHAR(32)    DEFAULT NULL COMMENT '异常类型: USER_ABORT/HOSPITAL_ISSUE/SAFETY/OTHER' AFTER refunded_amount,
    ADD COLUMN abnormal_remark VARCHAR(255)   DEFAULT NULL COMMENT '异常说明(§7.15)' AFTER abnormal_type,
    ADD KEY idx_city_code_status (city_code, order_status),
    ADD KEY idx_updated_at (updated_at);

-- 历史数据回填城市码：现有 city 存的是中文名，无法反推国标码，
-- 故统一置为全国默认 'DEFAULT'（与 service_price_rule.city_code 口径一致），
-- 待 Q1 城市确定后由业务方按 city 中文名逐条更正为真实国标码。
UPDATE service_order SET city_code = 'DEFAULT' WHERE city_code IS NULL;

-- ---------------------------------------------------------------
-- H-12：worker_profile 补城市码（筛选与就近排序口径）
-- ---------------------------------------------------------------
ALTER TABLE worker_profile
    ADD COLUMN city_code VARCHAR(16) DEFAULT NULL COMMENT '常驻城市国标6位码(H-12, 与 sys_dict/定价规则同口径)' AFTER city;

UPDATE worker_profile SET city_code = 'DEFAULT' WHERE city_code IS NULL;

-- ---------------------------------------------------------------
-- H-13：wallet_account 补累计与提现中金额
-- 说明：balance 是总余额（可用 = balance - frozen，见附录 B.3），
--       withdrawing 为提现处理中金额，total_* 为累计统计（只增不减）
-- ---------------------------------------------------------------
ALTER TABLE wallet_account
    ADD COLUMN withdrawing    DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '提现处理中金额(§7.18)' AFTER frozen,
    ADD COLUMN total_income   DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '累计收入(陪诊师视角, §3.6)' AFTER withdrawing,
    ADD COLUMN total_recharge DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '累计充值' AFTER total_income,
    ADD COLUMN total_expense  DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '累计支出(§3.6)' AFTER total_recharge;

-- 累计值按已存在的资金流水回填，保证历史账户口径一致
UPDATE wallet_account a
    JOIN (
        SELECT user_id,
               SUM(CASE WHEN type = 'INCOME'   THEN amount ELSE 0 END) AS income_sum,
               SUM(CASE WHEN type = 'RECHARGE' THEN amount ELSE 0 END) AS recharge_sum,
               SUM(CASE WHEN type = 'PAY'      THEN amount ELSE 0 END) AS expense_sum
        FROM wallet_transaction
        GROUP BY user_id
    ) t ON t.user_id = a.user_id
SET a.total_income   = t.income_sum,
    a.total_recharge = t.recharge_sum,
    a.total_expense  = t.expense_sum;
