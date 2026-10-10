-- 阶段 4：资金闭环与售后（契约附录 C/D、§3.4/§3.8/§5.14/§7.15/§7.18）
-- 约束遵循附录 D：无 AUTO_INCREMENT（雪花 ID）、无逻辑删除、utf8mb4_0900_ai_ci、
-- created_at/updated_at 默认值、金额 DECIMAL(12,2)、状态枚举名 VARCHAR(32)、无 version 的表不硬加。
--
-- 编号说明：附录 D 把这三张表规划在 V11，但阶段 1.5 已顺延占用 V11~V16，故取 V17。

-- ===============================================================
-- §3.8 / §3.9 充值单
-- ===============================================================
CREATE TABLE recharge_order (
    id            BIGINT        NOT NULL COMMENT '主键',
    recharge_no   VARCHAR(32)   NOT NULL COMMENT '充值单号, RC + yyyyMMddHHmmss + 6位随机',
    user_id       BIGINT        NOT NULL COMMENT '充值用户ID',
    amount        DECIMAL(12, 2) NOT NULL COMMENT '充值金额',
    preset_code   VARCHAR(16)   DEFAULT NULL COMMENT '预设档位码(C50/C100/C500), 仅统计用',
    status        VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待支付/SUCCESS成功/FAILED失败/EXPIRED超时',
    txn_no        VARCHAR(32)   DEFAULT NULL COMMENT '成功后的 RECHARGE 流水号',
    expire_at     DATETIME      DEFAULT NULL COMMENT '支付超时时间(默认15分钟), 超时由定时任务置 EXPIRED',
    paid_at       DATETIME      DEFAULT NULL COMMENT '支付成功时间',
    remark        VARCHAR(255)  DEFAULT NULL COMMENT '备注',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_recharge_no (recharge_no),
    KEY idx_user_time (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '充值单表';

-- ===============================================================
-- §7.18 / §7.19 提现申请
-- 提现是资金出口：申请时即扣减 balance 并累加 withdrawing，审核驳回才原路退回。
-- ===============================================================
CREATE TABLE withdraw_apply (
    id           BIGINT        NOT NULL COMMENT '主键',
    withdraw_no  VARCHAR(32)   NOT NULL COMMENT '提现单号, WD + yyyyMMddHHmmss + 6位随机',
    worker_id    BIGINT        NOT NULL COMMENT '申请陪诊师用户ID',
    amount       DECIMAL(12, 2) NOT NULL COMMENT '申请金额',
    fee          DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '手续费, 本项目恒为0.00(字段保留)',
    actual_amount DECIMAL(12, 2) NOT NULL COMMENT '实际到账 = amount - fee',
    channel      VARCHAR(16)   NOT NULL DEFAULT 'WECHAT' COMMENT '提现渠道: WECHAT(唯一实现)/BANK(预留)',
    status       VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待审核/SUCCESS已打款/REJECT已驳回',
    remark       VARCHAR(100)  DEFAULT NULL COMMENT '申请人备注',
    audit_remark VARCHAR(200)  DEFAULT NULL COMMENT '审核备注(驳回原因, 展示给陪诊师)',
    audit_admin_id BIGINT      DEFAULT NULL COMMENT '审核管理员ID(阶段7)',
    audit_time   DATETIME      DEFAULT NULL COMMENT '审核时间',
    finished_at  DATETIME      DEFAULT NULL COMMENT '打款完成时间',
    version      INT           NOT NULL DEFAULT 0 COMMENT '乐观锁(审核并发保护)',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_withdraw_no (withdraw_no),
    -- 「同一时刻只允许一笔处理中」由服务层校验(4007)；此处给查处理中单的索引
    KEY idx_worker_status (worker_id, status),
    KEY idx_status_time (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '提现申请表';

-- ===============================================================
-- §5.14 / §5.15 申诉工单
-- 用户对 40/50/70 状态订单发起；一笔订单同一 appeal_type 只允许一条(uk_order_type)。
-- auto_confirm_paused 记录「该单的48h自动确认已被暂停」，供阶段5定时任务排除。
-- ===============================================================
CREATE TABLE appeal_ticket (
    id                BIGINT       NOT NULL COMMENT '主键',
    appeal_no         VARCHAR(32)  NOT NULL COMMENT '申诉单号, AP + yyyyMMddHHmmss + 6位随机',
    order_no          VARCHAR(32)  NOT NULL COMMENT '关联订单号',
    applicant_id      BIGINT       NOT NULL COMMENT '申诉人用户ID(用户或陪诊师)',
    applicant_role    VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT '申诉人角色: USER/WORKER/SYSTEM',
    appeal_type       VARCHAR(32)  NOT NULL COMMENT '申诉类型(附录 B.7 AppealType)',
    reason            VARCHAR(500) NOT NULL COMMENT '申诉说明',
    evidence_urls     VARCHAR(1500) DEFAULT NULL COMMENT '证据图片相对路径, 逗号分隔, 最多6张',
    expect_refund_amount DECIMAL(12, 2) DEFAULT NULL COMMENT '期望退款金额, 仅仲裁参考',
    contact_phone     VARCHAR(16)  DEFAULT NULL COMMENT '回访电话, 不传用账号手机号',
    amount            DECIMAL(12, 2) DEFAULT NULL COMMENT '争议订单金额快照(便于列表展示)',
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING处理中/PROCESSING仲裁中/CLOSED已结案',
    auto_confirm_paused TINYINT    NOT NULL DEFAULT 0 COMMENT '是否已暂停该单48h自动确认: 1是 0否',
    arbitrate_result  VARCHAR(32)  DEFAULT NULL COMMENT '仲裁结果: UPHOLD/PARTIAL_REFUND/FULL_REFUND/SUPPORT_APPLICANT/SUPPORT_RESPONDENT/PARTIAL',
    refund_amount     DECIMAL(12, 2) DEFAULT NULL COMMENT '实际退款金额',
    conclusion        VARCHAR(500) DEFAULT NULL COMMENT '裁决说明(双方可见)',
    admin_id          BIGINT       DEFAULT NULL COMMENT '处理管理员ID(阶段7)',
    admin_remark      VARCHAR(500) DEFAULT NULL COMMENT '处理说明(对用户可见措辞)',
    handle_time       DATETIME     DEFAULT NULL COMMENT '结案时间',
    version           INT          NOT NULL DEFAULT 0 COMMENT '乐观锁(仲裁并发保护)',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_appeal_no (appeal_no),
    -- 「一单一诉」的数据库级兜底(§5.14 的 5002)
    UNIQUE KEY uk_order_type (order_no, appeal_type),
    KEY idx_applicant (applicant_id, created_at),
    KEY idx_status_time (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '申诉工单表';

-- ===============================================================
-- 管理端操作审计（契约 §2.3 资金审计、§9 阶段7 用）
-- 阶段 4 起即使后台界面未做，人工干预也必须留痕：含变更前后快照。
-- 本表只增不改不删。
-- ===============================================================
CREATE TABLE admin_operation_log (
    id              BIGINT       NOT NULL COMMENT '主键',
    admin_id        BIGINT       DEFAULT NULL COMMENT '操作管理员ID(阶段7走 admin_account)',
    admin_name      VARCHAR(64)  DEFAULT NULL COMMENT '操作人展示名',
    action_type     VARCHAR(32)  NOT NULL COMMENT '操作类型: CANCEL_ORDER/REFUND/ARBITRATE/WITHDRAW_APPROVE/WITHDRAW_REJECT/AUDIT_WORKER/ADJUST_CREDIT',
    target_type     VARCHAR(32)  DEFAULT NULL COMMENT '目标类型: ORDER/APPEAL/WITHDRAW/WORKER',
    target_id       VARCHAR(64)  DEFAULT NULL COMMENT '目标标识(订单号/申诉号/提现号等)',
    before_snapshot VARCHAR(2000) DEFAULT NULL COMMENT '变更前关键字段快照 JSON',
    after_snapshot  VARCHAR(2000) DEFAULT NULL COMMENT '变更后关键字段快照 JSON',
    remark          VARCHAR(500) DEFAULT NULL COMMENT '操作说明',
    ip              VARCHAR(64)  DEFAULT NULL COMMENT '操作来源IP',
    user_agent      VARCHAR(255) DEFAULT NULL COMMENT '操作来源UA',
    trace_id        VARCHAR(64)  DEFAULT NULL COMMENT '链路ID, 与响应体 traceId 串联排障',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_admin_time (admin_id, created_at),
    KEY idx_target (target_type, target_id),
    KEY idx_action_time (action_type, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '管理端操作审计表';

-- ===============================================================
-- 补列（附录 H-13 / H-10 中属阶段 4 的部分）
-- ===============================================================
-- wallet_account 的 withdrawing/total_* 已在 V14 补齐，此处无需重复。
-- service_order 的 refunded_amount/abnormal_type/abnormal_remark 已在 V14 补齐。

-- 提现的 biz_no 幂等键：wallet_transaction 的 uk_biz_no_type 已在 V12 建好，
-- 提现流水取 biz_no = withdraw_no，与 RECHARGE 的 recharge_no 天然区分，无需额外 DDL。

-- ===============================================================
-- 字典补充：提现渠道（§7.18 channel 枚举）
-- ===============================================================
INSERT INTO sys_dict (id, dict_type, item_value, item_label, sort, enabled, extra) VALUES
    (50030, 'WITHDRAW_CHANNEL', 'WECHAT', '微信零钱', 1, 1, '{"enabled":true}'),
    (50031, 'WITHDRAW_CHANNEL', 'BANK',   '银行卡',   2, 1, '{"enabled":false,"desc":"暂未开放"}');

-- 申诉类型字典（与附录 B.7 AppealType 对应，供前端下拉）
INSERT INTO sys_dict (id, dict_type, item_value, item_label, sort, enabled, extra) VALUES
    (50032, 'APPEAL_TYPE', 'SERVICE_NOT_PROVIDED', '陪诊师未按约提供服务', 1, 1, '{"applicantRole":"USER"}'),
    (50033, 'APPEAL_TYPE', 'ATTITUDE_ISSUE',       '服务态度问题',         2, 1, '{"applicantRole":"USER"}'),
    (50034, 'APPEAL_TYPE', 'EXTRA_CHARGE',         '线下额外索费',         3, 1, '{"applicantRole":"USER"}'),
    (50035, 'APPEAL_TYPE', 'NO_SHOW',              '陪诊师爽约',           4, 1, '{"applicantRole":"USER"}'),
    (50036, 'APPEAL_TYPE', 'USER_NO_SHOW',         '求助者爽约',           5, 1, '{"applicantRole":"WORKER"}'),
    (50037, 'APPEAL_TYPE', 'MALICIOUS_REVIEW',     '恶意差评',             6, 1, '{"applicantRole":"WORKER"}'),
    (50038, 'APPEAL_TYPE', 'OTHER',                '其他',                 7, 1, '{"applicantRole":"BOTH"}');

-- ===============================================================
-- 客户端配置补充（§8.3 功能开关：阶段 4 上线后开放提现与入驻申请入口）
-- 注意：featureFlags 是灰度开关的唯一入口。本轮实现已完成，故置 true；
--      若要灰度可随时改回 false，前端会隐藏入口、后端写接口返回 1006。
-- ===============================================================
UPDATE sys_config
SET config_value = '{"aiAssist":true,"demandOrder":true,"workerApply":true,"review":true,"withdraw":true,"virtualPhone":true,"appendReview":true}'
WHERE config_key = 'featureFlags';
