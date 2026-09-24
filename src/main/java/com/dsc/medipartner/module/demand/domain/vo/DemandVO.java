package com.dsc.medipartner.module.demand.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 需求工单视图（接口文档 4.3 列表 / 4.4 详情）。
 * 列表不返回 departments；详情返回完整推荐数组（取自 ai_recommend_json 快照）。
 */
@Data
public class DemandVO {

    private String demandId;
    private String demandNo;
    private String symptomDesc;
    private String city;
    private String hospitalPref;
    private LocalDateTime serviceTime;
    /** OPEN / ORDERED / CLOSED */
    private String status;
    /** 首选推荐科室名（快照 departments[0]），无快照为 null */
    private String topDepartment;
    private String orderNo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 仅详情接口返回 */
    private List<DepartmentVO> departments;
}
