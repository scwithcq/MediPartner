CREATE TABLE wallet_account (
    id         BIGINT        NOT NULL COMMENT '主键',
    user_id    BIGINT        NOT NULL COMMENT '用户ID',
    balance    DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '可用余额',
    frozen     DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '冻结余额',
    version    INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '钱包账户表';

CREATE TABLE wallet_transaction (
    id             BIGINT        NOT NULL COMMENT '主键',
    txn_no         VARCHAR(32)   NOT NULL COMMENT '流水号(幂等键)',
    user_id        BIGINT        NOT NULL COMMENT '用户ID',
    order_no       VARCHAR(32)   DEFAULT NULL COMMENT '关联订单号',
    type           VARCHAR(16)   NOT NULL COMMENT '类型: FREEZE/UNFREEZE/PAY/INCOME/REFUND/RECHARGE',
    amount         DECIMAL(12, 2) NOT NULL COMMENT '变动金额(正数)',
    balance_before DECIMAL(12, 2) NOT NULL COMMENT '变动前余额',
    balance_after  DECIMAL(12, 2) NOT NULL COMMENT '变动后余额',
    frozen_before  DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '变动前冻结额',
    frozen_after   DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '变动后冻结额',
    related_txn_no VARCHAR(32)   DEFAULT NULL COMMENT '关联流水号',
    remark         VARCHAR(255)  DEFAULT NULL COMMENT '备注',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_txn_no (txn_no),
    KEY idx_user_id (user_id),
    KEY idx_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '资金流水表';
