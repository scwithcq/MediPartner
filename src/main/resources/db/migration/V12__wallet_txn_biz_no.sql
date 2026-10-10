-- 阶段 1.5：资金幂等键（契约附录 H-2）
-- 背景：wallet_transaction.txn_no 由 OrderNoGenerator.next("TX") 随机生成，V3 的 uk_txn_no 只防随机碰撞，
--       防不住业务重复入账；ErrorCode.WALLET_TXN_DUPLICATE(4002) 定义了但全项目从未抛出。
-- 处置：新增 biz_no 业务幂等键列 + (biz_no, type) 复合唯一索引；
--       取值如 orderNo（结算/冻结）、withdrawNo、rechargeNo；重复插入由 DB 唯一键拦截，捕获 DuplicateKeyException 后抛 4002。
-- 注意：V3 的 uk_txn_no 单列唯一索引是既成事实，不可修改（附录 D 约束 4）。
ALTER TABLE wallet_transaction
    ADD COLUMN biz_no VARCHAR(64) DEFAULT NULL COMMENT '业务幂等键(orderNo/withdrawNo/rechargeNo等的混合值), 与 type 组成唯一键' AFTER txn_no,
    ADD UNIQUE KEY uk_biz_no_type (biz_no, type);

-- 回填历史数据的业务幂等键：结算/冻结/解冻/退款类流水以 order_no 为 biz_no。
-- 历史数据可能存在同一 order_no + type 的多条记录（旧随机 txn_no 无业务防重），
-- 回填会命中唯一键冲突，故仅回填该组合唯一的历史行，冲突行留 NULL（NULL 不参与唯一索引约束）。
UPDATE wallet_transaction t
    JOIN (
        SELECT order_no, type, MIN(id) AS keep_id
        FROM wallet_transaction
        WHERE order_no IS NOT NULL
        GROUP BY order_no, type
        HAVING COUNT(*) = 1
    ) k ON t.id = k.keep_id
SET t.biz_no = t.order_no
WHERE t.biz_no IS NULL;
