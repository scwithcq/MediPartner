-- 阶段 5：体验增强 —— 定位与就近排序建表补列（契约 §7.4 / §7.5 / §7.6，开发概括 §7.2）
--
-- 前置：V14 已给 worker_profile 补 city_code，V15 已建 sys_dict/sys_config，V17 已建申诉工单表。
--
-- 本脚本只做一件事：给 worker_profile 补经纬度列，支撑
--   ① §7.4 PUT /api/worker/profile/geo 上报定位
--   ② §7.5 接单大厅 / §6.1 陪诊师列表的「就近排序」与 distanceKm 下发
--   ③ §7.6 抢单距离校验（超出 medi.order.max-distance-km 返回 3006）
--
-- 为什么只加 geo_hash 不够（V1 已有该列）：
--   geo_hash 只能做「前缀粗筛」（前 6 位约 1.2km 精度），**无法还原精确距离**。
--   契约 §7.4 备注 1 明确要求「前缀粗筛 + 内存精算距离」，因此必须持久化原始经纬度。
--
-- 约束遵循 §6.2 / 附录 D：无 AUTO_INCREMENT（主键为雪花 ID）、无逻辑删除、
-- utf8mb4_0900_ai_ci、created_at/updated_at 带默认值。

-- ---------------------------------------------------------------
-- worker_profile 补定位列
-- DECIMAL(10,6) 与契约 §7.4「字符串保留 6 位小数」精度一致：
--   经度范围 ±180.000000 → DECIMAL(10,6) 最大 9999.999999，容纳有余
--   纬度范围 ±90.000000  → 同样容纳
-- 刻意不用 FLOAT/DOUBLE：二进制浮点在距离比较的边界值上会产生
-- 「同一坐标两次上报，一次通过一次不通过」的不可复现问题。
-- ---------------------------------------------------------------
ALTER TABLE worker_profile
    ADD COLUMN gps_lat        DECIMAL(10, 6) DEFAULT NULL COMMENT '最新上报纬度(§7.4, 6位小数)' AFTER geo_hash,
    ADD COLUMN gps_lng        DECIMAL(10, 6) DEFAULT NULL COMMENT '最新上报经度(§7.4, 6位小数)' AFTER gps_lat,
    ADD COLUMN geo_updated_at DATETIME       DEFAULT NULL COMMENT '定位最近上报时间(判断定位是否新鲜)' AFTER gps_lng;

-- 就近排序的支撑索引：先按 geo_hash 前缀粗筛，再按上报时间过滤过期定位。
-- 不加 (gps_lat, gps_lng) 复合索引——距离计算在内存完成，SQL 不参与排序（§1.8 白名单约束）。
ALTER TABLE worker_profile
    ADD KEY idx_geo_hash_updated (geo_hash, geo_updated_at);

-- ---------------------------------------------------------------
-- 阶段 5 新增配置项（§8.3 下发 + 后端自用）
-- ⚠ ID 从 60101 起：V15 已占用 60001~60005，直接续用会撞主键。
-- 用 INSERT IGNORE 而非 INSERT：uk_config_key 唯一，若运维已手工写入同键则不报错、不覆盖其值。
-- ---------------------------------------------------------------
INSERT IGNORE INTO sys_config (id, config_key, config_value, remark) VALUES
    (60101, 'apiCompatVersion', '1',
     '接口契约版本号(§8.4)：每次破坏性变更 +1，前端据此决定是否清缓存重拉字典与配置'),
    (60102, 'latestVersion', '1.0.0',
     '最新小程序版本号(§8.4)'),
    (60103, 'minSupportVersion', '1.0.0',
     '最低可用小程序版本号(§8.4)，低于此版本前端弹强制更新'),
    (60104, 'subscribeTemplateIds',
     '{"ORDER_ACCEPTED":"","SERVICE_STARTED":"","PENDING_CONFIRM":"","AUDIT_RESULT":"","APPEAL_RESULT":""}',
     '微信订阅消息模板ID(§8.3/§11 Q10)：未填期间只记日志不发送，不阻塞主流程');
