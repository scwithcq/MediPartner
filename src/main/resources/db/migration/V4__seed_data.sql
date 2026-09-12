-- 管理员（角色掩码 1用户 + 4管理员 = 5）
INSERT INTO sys_user (id, openid, phone, nickname, role_mask, status)
VALUES (10001, 'mock_openid_admin', NULL, '平台管理员', 5, 1);
INSERT INTO wallet_account (id, user_id, balance, frozen, version)
VALUES (20001, 10001, 0.00, 0.00, 0);

-- 测试求助者（角色掩码 1用户）
INSERT INTO sys_user (id, openid, phone, nickname, role_mask, status)
VALUES (10002, 'mock_openid_user1', NULL, '测试求助者', 1, 1);
INSERT INTO wallet_account (id, user_id, balance, frozen, version)
VALUES (20002, 10002, 500.00, 0.00, 0);

-- 测试陪诊师（角色掩码 1用户 + 2陪诊师 = 3）
INSERT INTO sys_user (id, openid, phone, nickname, role_mask, status)
VALUES (10003, 'mock_openid_worker1', NULL, '测试陪诊师', 3, 1);
INSERT INTO worker_profile (id, user_id, real_name, audit_status, credit_score)
VALUES (30001, 10003, '张三', 'PASS', 100);
INSERT INTO wallet_account (id, user_id, balance, frozen, version)
VALUES (20003, 10003, 100.00, 0.00, 0);
