package com.dsc.medipartner.common.result;

import lombok.Data;

/**
 * 分页请求公共入参（契约 §1.3）。
 *
 * <p>字段名与 §1.3 <b>逐字一致</b>：{@code pageNum} / {@code pageSize} / {@code sortBy} /
 * {@code order} / {@code updatedSince}。注意排序方向字段叫 {@code order}，<b>不是</b>
 * {@code sortOrder}（附录 H-9 明确点名过这个易错处）。
 *
 * <p>{@code sortBy} <b>必须</b>经 {@link SortWhitelist} 校验：直接把它拼进 SQL 等于开注入口，
 * 白名单是唯一可靠防线（黑名单过滤永远有绕过）。
 */
@Data
public class PageQuery {

    /** 页码，从 1 开始（§1.3：≥ 1） */
    private long pageNum = 1;

    /** 每页条数（§1.3：1~100，超限由 {@link #getEffectivePageSize()} 兜底为 100） */
    private long pageSize = 10;

    /**
     * 排序字段（§1.3 / §1.8 白名单）。
     * 非法值<b>忽略并回落该接口默认排序，不报错</b>（§1.8 明确要求），由 SortWhitelist 处理。
     */
    private String sortBy;

    /** 排序方向：{@code asc} / {@code desc}，默认 {@code desc}（§1.3） */
    private String order;

    /**
     * 增量轮询起点（§1.9）：{@code yyyy-MM-dd HH:mm:ss}，只返回 {@code updated_at > updatedSince} 的记录。
     * 留空表示全量拉取第一页。
     */
    private String updatedSince;

    /** §1.3 约定 pageSize 上限 100，超出兜底为 100；下限兜底为 1 */
    public long getEffectivePageSize() {
        if (pageSize < 1) {
            return 1;
        }
        return Math.min(pageSize, 100);
    }

    /** §1.3 约定 pageNum ≥ 1 */
    public long getEffectivePageNum() {
        return pageNum < 1 ? 1 : pageNum;
    }
}
