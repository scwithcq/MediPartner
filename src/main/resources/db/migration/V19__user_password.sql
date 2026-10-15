ALTER TABLE sys_user ADD COLUMN password_hash VARCHAR(100) DEFAULT NULL COMMENT 'BCrypt 登录密码摘要';
ALTER TABLE sys_user ADD UNIQUE KEY uk_user_phone (phone);
