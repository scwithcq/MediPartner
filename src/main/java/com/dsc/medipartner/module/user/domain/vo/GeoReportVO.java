package com.dsc.medipartner.module.user.domain.vo;

import lombok.Data;

/**
 * §7.4 上报定位响应。
 *
 * <p>{@code nearbyOrderCount} 让前端上报后能立刻刷新红点，省掉一次大厅请求（契约 §7.4 备注 2）。
 */
@Data
public class GeoReportVO {

    /** GeoHash 前 6 位（约 1.2km 精度），供前端调试与地图打点 */
    private String geoHash;

    /** 当前附近可接单数（接单大厅候选量），用于红点提示 */
    private Integer nearbyOrderCount;
}
