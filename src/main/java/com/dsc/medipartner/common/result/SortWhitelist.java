package com.dsc.medipartner.common.result;

import java.util.Map;
import java.util.Set;

/**
 * 排序字段白名单（契约 §1.3、§1.8）。
 *
 * <p><b>安全前提</b>：{@code sortBy} 来自 HTTP query，是不可信输入。本类把每个接口允许的取值
 * 映射到<b>硬编码的物理列名</b>（{@link #COLUMNS}），调用方只能拿到映射后的常量，
 * <b>永远不拼接前端原始字符串</b>。因此 SQL 中出现的排序列名必定来自本类常量。
 *
 * <p>非法值一律回落该接口的默认排序并且<b>不报错</b>（§1.8 明确要求：避免前端小改动导致 500）。
 *
 * <p>用法：
 * <pre>{@code
 * SortWhitelist.Order ord = SortWhitelist.Spec.ORDER_LIST.resolve(q.getSortBy(), q.getOrder());
 * wrapper.last("ORDER BY " + ord.sqlColumn() + (ord.asc() ? " ASC" : " DESC"));
 * }</pre>
 */
public final class SortWhitelist {

    /** §1.8 白名单表：接口 -> 允许的排序属性名（全部为硬编码常量，与请求无关） */
    private static final Map<Spec, Set<String>> ALLOWED = Map.of(
            Spec.ORDER_LIST, Set.of("createdAt", "serviceTime", "amount"),
            Spec.ORDER_HALL_ASC, Set.of("serviceTime", "amount", "createdAt", "distance"),
            Spec.WORKER_LIST, Set.of("goodRate", "serviceCount", "creditScore", "distance"),
            Spec.WALLET_TXN, Set.of("createdAt"),
            Spec.ADMIN_LIST, Set.of("createdAt"));

    /**
     * 属性名 -> 数据库物理列名。<b>这是全项目唯一的排序字段映射来源</b>，
     * SQL 里出现的排序列名只能取自本 Map。
     */
    private static final Map<String, String> COLUMNS = Map.of(
            "createdAt", "created_at",
            "serviceTime", "service_time",
            "amount", "amount",
            "goodRate", "good_rate",
            "serviceCount", "service_count",
            "creditScore", "credit_score",
            // 阶段 5 才实现真实距离计算，此处仅占位；阶段 1.5 由服务层回落默认排序
            "distance", "geo_hash");

    private SortWhitelist() {
    }

    /** 一个接口允许的排序字段集合 + 默认排序 */
    public enum Spec {

        /** §1.8：GET /api/order/list、GET /api/worker/order/list，默认 createdAt desc */
        ORDER_LIST("createdAt", false),

        /** §1.8：GET /api/worker/order/hall，默认 serviceTime asc */
        ORDER_HALL_ASC("serviceTime", true),

        /** §1.8：GET /api/user/worker/list，默认 goodRate desc */
        WORKER_LIST("goodRate", false),

        /** §1.8：GET /api/wallet/transactions，默认 createdAt desc */
        WALLET_TXN("createdAt", false),

        /** §1.8：管理端列表默认 createdAt desc（阶段 7，先冻结） */
        ADMIN_LIST("createdAt", false);

        private final String defaultProperty;
        private final boolean defaultAsc;

        Spec(String defaultProperty, boolean defaultAsc) {
            this.defaultProperty = defaultProperty;
            this.defaultAsc = defaultAsc;
        }

        /** 本接口允许的排序属性名 */
        public Set<String> allowedProperties() {
            return ALLOWED.get(this);
        }

        public String defaultProperty() {
            return defaultProperty;
        }

        public boolean defaultAsc() {
            return defaultAsc;
        }

        /**
         * 解析排序参数，永不返回 null、永不抛异常。
         *
         * @param sortBy 前端传入的排序字段，可空或非法
         * @param order  前端传入的方向（{@code asc}/{@code desc}），可空
         * @return 合法的、可安全使用的排序；非法输入回落到本接口默认值
         */
        public Order resolve(String sortBy, String order) {
            String property = defaultProperty;
            String candidate = sortBy == null ? null : sortBy.trim();
            // 命中本接口白名单才采用；否则回落默认，不报错（§1.8）
            if (candidate != null && allowedProperties().contains(candidate)) {
                property = candidate;
            }
            boolean asc;
            if ("asc".equalsIgnoreCase(order)) {
                asc = true;
            } else if ("desc".equalsIgnoreCase(order)) {
                asc = false;
            } else {
                asc = defaultAsc;
            }
            return new Order(property, COLUMNS.get(property), asc);
        }
    }

    /** 解析结果：安全的物理列名 + 方向 */
    public record Order(String property, String column, boolean asc) {

        /** 供 SQL 使用的安全列名（保证取自白名单映射，非请求原值） */
        public String sqlColumn() {
            return column;
        }
    }
}
