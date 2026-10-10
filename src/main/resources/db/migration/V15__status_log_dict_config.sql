-- 阶段 1.5：订单流转日志 + 字典 + 客户端配置（契约附录 D、§5.5、§8.2、§8.3）
-- 约束遵循附录 D：无 AUTO_INCREMENT（雪花 ID）、无逻辑删除、utf8mb4_0900_ai_ci、created_at/updated_at 默认值
--
-- 编号说明：附录 D 把这三张表规划在 V6/V7，但 V6/V7 是历史空号（阶段 2 实际占用 V8/V8.1，
-- 阶段 3 占用 V9/V10，阶段 1.5 已顺延使用 V11~V14），故本脚本取 V15。

-- ---------------------------------------------------------------
-- §5.5 / §7.9 / §9.15：订单流转日志
-- 由状态机 listener 统一落库，业务代码不手写日志，保证不漏记
-- operator_type 见附录 B.10（USER/WORKER/ADMIN/SYSTEM）
-- ---------------------------------------------------------------
CREATE TABLE order_status_log (
    id            BIGINT       NOT NULL COMMENT '主键',
    order_no      VARCHAR(32)  NOT NULL COMMENT '订单号',
    from_state    INT          DEFAULT NULL COMMENT '变更前状态码(NULL=下单初始)',
    to_state      INT          NOT NULL COMMENT '变更后状态码',
    event         VARCHAR(32)  DEFAULT NULL COMMENT '触发事件(附录 B.2 OrderEvent)',
    operator_type VARCHAR(16)  NOT NULL DEFAULT 'SYSTEM' COMMENT '操作方: USER/WORKER/ADMIN/SYSTEM',
    operator_id   BIGINT       DEFAULT NULL COMMENT '操作人用户ID(SYSTEM 为 NULL)',
    operator_name VARCHAR(64)  DEFAULT NULL COMMENT '操作人展示名(脱敏后落库)',
    title         VARCHAR(64)  DEFAULT NULL COMMENT '展示标题, 如「陪诊师已接单」',
    remark        VARCHAR(255) DEFAULT NULL COMMENT '补充说明(取消原因/异常说明/位置偏离等)',
    gps_lat       VARCHAR(16)  DEFAULT NULL COMMENT '操作时定位纬度',
    gps_lng       VARCHAR(16)  DEFAULT NULL COMMENT '操作时定位经度',
    snapshot      TEXT         DEFAULT NULL COMMENT '变更前后关键字段快照 JSON(管理端仲裁用, §9.15)',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_order_time (order_no, created_at),
    KEY idx_to_state (to_state)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '订单流转日志表';

-- ---------------------------------------------------------------
-- §8.2：字典（城市/服务类型/取消原因/异常类型/评价标签/信用规则）
-- extra 存 JSON 字符串，键含义见契约 §8.2 的 extra 关键字段列
-- ---------------------------------------------------------------
CREATE TABLE sys_dict (
    id         BIGINT       NOT NULL COMMENT '主键',
    dict_type  VARCHAR(32)  NOT NULL COMMENT '字典类型: CITY/SERVICE_TYPE/CANCEL_REASON/ABNORMAL_TYPE/REVIEW_TAG/CREDIT_RULE',
    item_value VARCHAR(64)  NOT NULL COMMENT '条目码值',
    item_label VARCHAR(128) NOT NULL COMMENT '条目标签(中文, 前端直接展示)',
    sort       INT          NOT NULL DEFAULT 0 COMMENT '排序, 升序',
    enabled    TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用: 1启用 0停用(不下发)',
    extra      VARCHAR(500) DEFAULT NULL COMMENT '扩展字段 JSON, 按 dict_type 约定键',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_value (dict_type, item_value),
    KEY idx_type_sort (dict_type, sort)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '字典表';

-- ---------------------------------------------------------------
-- §8.3：客户端配置（轮询间隔/功能开关/上传限制/协议地址等）
-- 读不到时由代码内置默认值兜底，保证小程序任何情况下都能启动
-- ---------------------------------------------------------------
CREATE TABLE sys_config (
    id           BIGINT       NOT NULL COMMENT '主键',
    config_key   VARCHAR(64)  NOT NULL COMMENT '配置键',
    config_value VARCHAR(500) DEFAULT NULL COMMENT '配置值(JSON 或标量文本)',
    remark       VARCHAR(255) DEFAULT NULL COMMENT '说明',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_config_key (config_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '客户端配置表';

-- ================= 字典种子数据（附录 B.6 / §8.2） =================

-- 已开通城市：Q1 尚未拍板具体城市，先落一个全国默认项；
-- Q1 确定后由管理端 §9.32 追加真实城市（value=国标 6 位码），无需改代码。
INSERT INTO sys_dict (id, dict_type, item_value, item_label, sort, enabled, extra) VALUES
    (50001, 'CITY', 'DEFAULT', '全国', 1, 1, '{"hospitals":[]}');

-- 服务类型：与 ServiceType 枚举、service_price_rule 一一对应（附录 B.6）
-- 注意：extra 里的价格仅供展示，真实计价一律以 §5.1 price-preview 为准
INSERT INTO sys_dict (id, dict_type, item_value, item_label, sort, enabled, extra) VALUES
    (50002, 'SERVICE_TYPE', 'ACCOMPANY',     '全程陪诊', 1, 1, '{"basePrice":"200.00","extraHourPrice":"30.00","unit":"小时","desc":"全程陪同就诊，含 4 小时"}'),
    (50003, 'SERVICE_TYPE', 'HALF_DAY',      '半日陪护', 2, 1, '{"basePrice":"150.00","extraHourPrice":"25.00","unit":"小时","desc":"半日陪同，含 3 小时"}'),
    (50004, 'SERVICE_TYPE', 'FULL_DAY',      '全日陪护', 3, 1, '{"basePrice":"280.00","extraHourPrice":"30.00","unit":"小时","desc":"全日陪同，含 8 小时"}'),
    (50005, 'SERVICE_TYPE', 'PICKUP_REPORT', '代取报告', 4, 1, '{"basePrice":"60.00","extraHourPrice":"0.00","unit":"次","desc":"代取报告/药品，一口价"}'),
    (50006, 'SERVICE_TYPE', 'CONSULT_AGENT', '代问诊',   5, 1, '{"basePrice":"120.00","extraHourPrice":"0.00","unit":"次","desc":"代患者面诊并回传结论，一口价"}');

-- 用户取消原因（§5.7）
INSERT INTO sys_dict (id, dict_type, item_value, item_label, sort, enabled, extra) VALUES
    (50007, 'CANCEL_REASON', 'CHANGE_PLAN',    '行程有变',       1, 1, NULL),
    (50008, 'CANCEL_REASON', 'TIME_CONFLICT',  '时间冲突',       2, 1, NULL),
    (50009, 'CANCEL_REASON', 'FOUND_OTHER',    '已找到其他陪诊', 3, 1, NULL),
    (50010, 'CANCEL_REASON', 'NO_LONGER_NEED', '暂不需要服务',   4, 1, NULL),
    (50011, 'CANCEL_REASON', 'OTHER',          '其他原因',       5, 1, NULL);

-- 陪诊师标记异常原因（§7.15）
INSERT INTO sys_dict (id, dict_type, item_value, item_label, sort, enabled, extra) VALUES
    (50012, 'ABNORMAL_TYPE', 'USER_ABORT',     '用户中止就诊', 1, 1, '{"needVoucher":true}'),
    (50013, 'ABNORMAL_TYPE', 'HOSPITAL_ISSUE', '院方原因',     2, 1, '{"needVoucher":true}'),
    (50014, 'ABNORMAL_TYPE', 'SAFETY',         '安全风险',     3, 1, '{"needVoucher":false}'),
    (50015, 'ABNORMAL_TYPE', 'OTHER',          '其它',         4, 1, '{"needVoucher":false}');

-- 评价印象标签（§5.11，与 ReviewTag 枚举一致）
INSERT INTO sys_dict (id, dict_type, item_value, item_label, sort, enabled, extra) VALUES
    (50016, 'REVIEW_TAG', 'PUNCTUAL',     '准时到达', 1, 1, '{"starMin":1}'),
    (50017, 'REVIEW_TAG', 'PATIENT',      '耐心细致', 2, 1, '{"starMin":1}'),
    (50018, 'REVIEW_TAG', 'PROFESSIONAL', '专业可靠', 3, 1, '{"starMin":1}'),
    (50019, 'REVIEW_TAG', 'WARM',         '热心周到', 4, 1, '{"starMin":1}'),
    (50020, 'REVIEW_TAG', 'EFFICIENT',    '高效便捷', 5, 1, '{"starMin":1}');

-- 信用分规则说明（§7.16，与 CreditChangeType 枚举一致）
INSERT INTO sys_dict (id, dict_type, item_value, item_label, sort, enabled, extra) VALUES
    (50021, 'CREDIT_RULE', 'ORDER_COMPLETED',  '按时完成订单',     1, 1, '{"score":"+2"}'),
    (50022, 'CREDIT_RULE', 'POSITIVE_REVIEW',  '获得好评',         2, 1, '{"score":"+1"}'),
    (50023, 'CREDIT_RULE', 'NEGATIVE_REVIEW',  '收到差评',         3, 1, '{"score":"-2"}'),
    (50024, 'CREDIT_RULE', 'TRACE_TIMEOUT',    '未按时上传留痕',   4, 1, '{"score":"-2"}'),
    (50025, 'CREDIT_RULE', 'LATE_OVER_15MIN',  '迟到超 15 分钟',   5, 1, '{"score":"-5"}'),
    (50026, 'CREDIT_RULE', 'COMPLAINT_UPHELD', '投诉申诉成立',     6, 1, '{"score":"-10"}'),
    (50027, 'CREDIT_RULE', 'NO_SHOW',          '爽约（申诉核实）', 7, 1, '{"score":"-20"}');

-- ================= 客户端配置种子（§8.3） =================
-- 说明：留空也能跑（SysConfigService 内置默认值兜底），此处落库便于运营改配置
INSERT INTO sys_config (id, config_key, config_value, remark) VALUES
    (60001, 'pollInterval',         '{"hall":10,"report":3,"orderDetail":15,"workerOrderList":15}', '轮询间隔(秒), §1.9'),
    (60002, 'featureFlags',         '{"aiAssist":true,"demandOrder":true,"workerApply":false,"review":true,"withdraw":false,"virtualPhone":true,"appendReview":true}', '功能开关, 未上线能力置 false'),
    (60003, 'uploadLimit',          '{"imageMaxMb":10,"pdfMaxMb":20,"traceMaxCount":20,"reviewImageMaxCount":3,"avatarMaxMb":5}', '上传限制'),
    (60004, 'servicePhone',         '400-000-0000', '平台客服电话'),
    (60005, 'agreements',           '{"user":"/files/agreements/user.html","worker":"/files/agreements/worker.html","privacy":"/files/agreements/privacy.html"}', '协议地址(相对路径)');
