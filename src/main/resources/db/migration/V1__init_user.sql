CREATE TABLE sys_user (
    id          BIGINT       NOT NULL COMMENT '主键',
    openid      VARCHAR(64)  NOT NULL COMMENT '微信OpenID',
    phone       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    nickname    VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
    avatar      VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
    role_mask   INT          NOT NULL DEFAULT 1 COMMENT '角色掩码: 1用户 2陪诊师 4管理员',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 1正常 0禁用',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_openid (openid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户表';

CREATE TABLE worker_profile (
    id              BIGINT       NOT NULL COMMENT '主键',
    user_id         BIGINT       NOT NULL COMMENT '用户ID',
    real_name       VARCHAR(64)  DEFAULT NULL COMMENT '真实姓名',
    id_card_no      VARCHAR(128) DEFAULT NULL COMMENT '身份证号(加密)',
    health_cert_url VARCHAR(255) DEFAULT NULL COMMENT '健康证/承诺书URL',
    audit_status    VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '审核状态: PENDING/REJECT/PASS',
    credit_score    INT          NOT NULL DEFAULT 100 COMMENT '信用分',
    geo_hash        VARCHAR(32)  DEFAULT NULL COMMENT '定位GeoHash',
    audit_by        BIGINT       DEFAULT NULL COMMENT '审核管理员ID',
    audit_time      DATETIME     DEFAULT NULL COMMENT '审核时间',
    audit_remark    VARCHAR(255) DEFAULT NULL COMMENT '审核备注',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '陪诊师扩展审核表';
