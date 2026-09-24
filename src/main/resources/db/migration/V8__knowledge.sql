-- 知识库：文档表 + 切片表（阶段 2 AI 能力与知识库）
-- 约束遵循附录 D：无 AUTO_INCREMENT（雪花 ID）、无逻辑删除、utf8mb4_0900_ai_ci
CREATE TABLE knowledge_doc (
    id           BIGINT       NOT NULL COMMENT '主键',
    title        VARCHAR(128) NOT NULL COMMENT '文档标题',
    file_url     VARCHAR(500) DEFAULT NULL COMMENT '原始文件存储路径/URL',
    source       VARCHAR(32)  NOT NULL DEFAULT 'UPLOAD' COMMENT '来源: UPLOAD后台上传 SEED内置语料',
    version      INT          NOT NULL DEFAULT 1 COMMENT '同名文档版本号, 从1递增',
    status       VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING待解析 PARSING解析中 ACTIVE已发布 FAILED解析失败 INACTIVE已下架',
    publish_time DATETIME     DEFAULT NULL COMMENT '发布(生效)时间',
    remark       VARCHAR(500) DEFAULT NULL COMMENT '备注: 解析失败原因/向量化降级说明',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_title_version (title, version),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库文档表';

CREATE TABLE knowledge_slice (
    id         BIGINT        NOT NULL COMMENT '主键',
    doc_id     BIGINT        NOT NULL COMMENT '所属文档ID',
    seq        INT           NOT NULL COMMENT '切片序号, 从1开始',
    content    VARCHAR(2000) NOT NULL COMMENT '切片文本内容',
    vector_id  VARCHAR(64)   DEFAULT NULL COMMENT '向量库文档ID(与切片ID字符串一致), 未向量化为NULL',
    status     VARCHAR(32)   NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING待处理 ACTIVE可检索 FAILED处理失败 INACTIVE已失效',
    created_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_doc_seq (doc_id, seq),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库切片表';
