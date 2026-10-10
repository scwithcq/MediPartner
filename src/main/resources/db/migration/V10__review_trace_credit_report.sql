-- 阶段 3：履约与评价（契约附录 D V10）
-- 约束遵循附录 D：无 AUTO_INCREMENT（雪花 ID）、无逻辑删除、utf8mb4_0900_ai_ci、created_at/updated_at 默认值

CREATE TABLE order_trace (
    id             BIGINT       NOT NULL COMMENT '主键',
    order_no       VARCHAR(32)  NOT NULL COMMENT '订单号',
    worker_id      BIGINT       NOT NULL COMMENT '上传陪诊师ID',
    type           VARCHAR(16)  NOT NULL DEFAULT 'TRACE' COMMENT '类型: TRACE过程留痕 VOUCHER结束凭证',
    seq            INT          NOT NULL DEFAULT 0 COMMENT '单内序号, 从1开始',
    image_url      VARCHAR(255) NOT NULL COMMENT '带水印照片相对路径',
    gps_lat        VARCHAR(16)  DEFAULT NULL COMMENT '拍摄定位纬度(6位小数字符串)',
    gps_lng        VARCHAR(16)  DEFAULT NULL COMMENT '拍摄定位经度(6位小数字符串)',
    address        VARCHAR(255) DEFAULT NULL COMMENT '逆地理编码地址描述',
    watermark_time DATETIME     DEFAULT NULL COMMENT '照片水印时间(小程序端合成)',
    node           VARCHAR(16)  DEFAULT NULL COMMENT '服务节点: ARRIVED/REGISTER/WAITING/VISIT/PHARMACY/OTHER',
    remark         VARCHAR(200) DEFAULT NULL COMMENT '陪诊师附言',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_order_no_seq (order_no, seq),
    KEY idx_worker_id (worker_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '订单服务留痕表';

-- 一单一评按角色维度兜底：USER 评陪诊师、WORKER 评用户各一条（契约 §5.12 myReview + workerReview）
CREATE TABLE review (
    id             BIGINT        NOT NULL COMMENT '主键',
    order_no       VARCHAR(32)   NOT NULL COMMENT '订单号',
    worker_id      BIGINT        DEFAULT NULL COMMENT '被评价/评价方陪诊师用户ID(冗余, 便于陪诊师侧查询)',
    reviewer_id    BIGINT        NOT NULL COMMENT '评价人用户ID',
    reviewer_role  VARCHAR(16)   NOT NULL COMMENT '评价人角色: USER/WORKER',
    star           TINYINT       NOT NULL COMMENT '星级 1~5',
    content        VARCHAR(500)  DEFAULT NULL COMMENT '文字评价',
    tags           VARCHAR(255)  DEFAULT NULL COMMENT '印象标签码值, 逗号分隔',
    image_urls     VARCHAR(1000) DEFAULT NULL COMMENT '评价配图相对路径, 逗号分隔, 最多3张',
    anonymous      TINYINT       NOT NULL DEFAULT 0 COMMENT '是否匿名: 1是 0否',
    append_content VARCHAR(500)  DEFAULT NULL COMMENT '追评内容(一评一追)',
    append_at      DATETIME      DEFAULT NULL COMMENT '追评时间',
    reply_content  VARCHAR(200)  DEFAULT NULL COMMENT '陪诊师回复(一评价一回复)',
    reply_at       DATETIME      DEFAULT NULL COMMENT '回复时间',
    review_status  TINYINT       NOT NULL DEFAULT 1 COMMENT '状态: 1生效 0被仲裁屏蔽',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_role (order_no, reviewer_role),
    KEY idx_worker_role (worker_id, reviewer_role, review_status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '评价表';

CREATE TABLE credit_change_log (
    id             BIGINT       NOT NULL COMMENT '主键',
    worker_user_id BIGINT       NOT NULL COMMENT '陪诊师用户ID',
    change_type    VARCHAR(32)  NOT NULL COMMENT '变动类型: ORDER_COMPLETED/POSITIVE_REVIEW/NEGATIVE_REVIEW/NO_SHOW/LATE_OVER_15MIN/TRACE_TIMEOUT/COMPLAINT_UPHELD/ADMIN_ADJUST',
    change_score   INT          NOT NULL COMMENT '变动分值(带正负号)',
    before_score   INT          NOT NULL COMMENT '变动前信用分',
    after_score    INT          NOT NULL COMMENT '变动后信用分',
    order_no       VARCHAR(32)  DEFAULT NULL COMMENT '关联订单号',
    review_id      BIGINT       DEFAULT NULL COMMENT '关联评价ID(好评/差评加减分溯源, §7.20 impactScore)',
    reason         VARCHAR(100) DEFAULT NULL COMMENT '中文原因短语(后端下发, 前端不映射)',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_worker_time (worker_user_id, created_at),
    KEY idx_review_id (review_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '信用分变动流水表';

CREATE TABLE ai_report (
    id           BIGINT       NOT NULL COMMENT '主键',
    order_no     VARCHAR(32)  NOT NULL COMMENT '订单号(一单一报告)',
    status       VARCHAR(16)  NOT NULL DEFAULT 'PROCESSING' COMMENT '状态: PROCESSING生成中 DONE已完成 FAILED失败',
    title        VARCHAR(128) DEFAULT NULL COMMENT '报告标题',
    summary      VARCHAR(1000) DEFAULT NULL COMMENT '摘要(≤300字)',
    sections     TEXT         DEFAULT NULL COMMENT '结构化正文 JSON: [{heading,content}]',
    advice       VARCHAR(1000) DEFAULT NULL COMMENT '后续建议 JSON 数组(≤5条)',
    provider     VARCHAR(16)  DEFAULT NULL COMMENT '生成厂商: deepseek/qwen/zhipu',
    degraded     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否降级模板生成: 1是 0否',
    fail_reason  VARCHAR(255) DEFAULT NULL COMMENT '失败原因(用户可见文案)',
    generated_at DATETIME     DEFAULT NULL COMMENT '生成完成时间',
    version      INT          NOT NULL DEFAULT 1 COMMENT '重生成版本号, 覆盖时自增',
    retry_count  INT          NOT NULL DEFAULT 0 COMMENT '已重试次数(上限3)',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI陪诊报告表';

-- worker_profile 补冗余统计列（契约附录 C；star_distribution 由 review 表聚合, 见进度文档偏差说明）
ALTER TABLE worker_profile
    ADD COLUMN city          VARCHAR(64)  DEFAULT NULL COMMENT '常驻城市',
    ADD COLUMN service_types VARCHAR(255) DEFAULT NULL COMMENT '可接单服务类型码值, 逗号分隔',
    ADD COLUMN intro         VARCHAR(500) DEFAULT NULL COMMENT '个人简介',
    ADD COLUMN good_rate     INT          NOT NULL DEFAULT 100 COMMENT '好评率百分比整数(star>=4计好评), 无评价为100',
    ADD COLUMN review_count  INT          NOT NULL DEFAULT 0 COMMENT '评价总数',
    ADD COLUMN service_count INT          NOT NULL DEFAULT 0 COMMENT '已完成订单数',
    ADD COLUMN avg_star      DECIMAL(2, 1) NOT NULL DEFAULT 5.0 COMMENT '平均星级(一位小数), 无评价为5.0',
    ADD COLUMN status        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 1上架 0下架(不出现在浏览列表)';
