package com.dsc.medipartner.module.user.domain.dto;

import com.dsc.medipartner.common.result.PageQuery;
import com.dsc.medipartner.common.result.SortWhitelist;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 陪诊师列表查询条件（契约 §6.1）。
 *
 * <p>{@code sortBy}/{@code order} 继承自 {@link PageQuery}（§1.3），合法性由
 * {@link SortWhitelist.Spec#WORKER_LIST} 统一校验（§1.8），Mapper 侧只接受
 * {@link #sortProperty()} 返回的白名单属性名，再用 {@code <choose>} 映射到固定列名，
 * 前端原始字符串不参与 SQL 拼接。
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

    /**
     * 已通过白名单校验的排序属性名（§1.8：goodRate / serviceCount / creditScore / distance）。
     * 非法值在此已回落为默认 {@code goodRate}，Mapper 无需再判空。
     */
    public String sortProperty() {
        return SortWhitelist.Spec.WORKER_LIST.resolve(getSortBy(), getOrder()).property();
    }

    /** 排序方向是否升序（默认 desc，§1.8） */
    public boolean isAsc() {
        return SortWhitelist.Spec.WORKER_LIST.resolve(getSortBy(), getOrder()).asc();
    }
}

