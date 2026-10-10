package com.dsc.medipartner.module.user.domain.dto;

import com.dsc.medipartner.common.result.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 陪诊师列表查询条件（契约 §6.1）。
 * sortBy 走白名单，Mapper 里用 &lt;choose&gt; 映射到固定列名，不接受前端拼列名，杜绝 SQL 注入；
 * distance 排序属阶段 5（需坐标与 geohash 计算），当前按 goodRate 兜底。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WorkerQuery extends PageQuery {

    private String city;
    private String serviceType;
    private String keyword;
    private Integer minGoodRate;
    private Integer minCreditScore;
    /** 阶段 5：传坐标后按距离升序并返回 distanceKm */
    private String gpsLat;
    private String gpsLng;
    /** 白名单：goodRate（默认）/ serviceCount / creditScore / distance */
    private String sortBy;
    /** asc / desc（默认 desc） */
    private String order;

    public boolean isAsc() {
        return "asc".equalsIgnoreCase(order);
    }
}
