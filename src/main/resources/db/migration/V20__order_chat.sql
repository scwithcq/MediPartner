CREATE TABLE chat_conversation (
 id BIGINT NOT NULL PRIMARY KEY, order_id BIGINT NOT NULL, order_no VARCHAR(32) NOT NULL,
 user_id BIGINT NOT NULL, worker_id BIGINT NOT NULL, accepted_at DATETIME NOT NULL,
 user_read_id BIGINT NOT NULL DEFAULT 0, worker_read_id BIGINT NOT NULL DEFAULT 0, purged_at DATETIME DEFAULT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_chat_pair (order_id,worker_id,accepted_at), KEY idx_chat_user (user_id,updated_at), KEY idx_chat_worker (worker_id,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE chat_message (
 id BIGINT NOT NULL PRIMARY KEY, conversation_id BIGINT NOT NULL, sender_id BIGINT NOT NULL,
 client_id VARCHAR(64) NOT NULL, message_type VARCHAR(8) NOT NULL, content VARCHAR(1000) DEFAULT NULL, attachment_id VARCHAR(64) DEFAULT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_chat_retry (conversation_id,sender_id,client_id), KEY idx_chat_history (conversation_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE chat_attachment (
 id BIGINT NOT NULL PRIMARY KEY, attachment_key VARCHAR(64) NOT NULL, conversation_id BIGINT NOT NULL, uploader_id BIGINT NOT NULL,
 storage_name VARCHAR(80) NOT NULL, content_type VARCHAR(30) NOT NULL, message_id BIGINT DEFAULT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_chat_attachment (attachment_key), KEY idx_chat_attachment_conversation (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE chat_access_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, admin_id BIGINT NOT NULL, order_no VARCHAR(32) NOT NULL,
 appeal_no VARCHAR(32) NOT NULL, reason VARCHAR(200) NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_chat_audit_order (order_no,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
