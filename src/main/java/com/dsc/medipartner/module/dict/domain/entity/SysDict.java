package com.dsc.medipartner.module.dict.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 字典表（契约 §8.2；表由 V15 建）。
 * 一份数据供城市选择、服务类型、取消原因、异常原因、评价标签、信用规则六处下拉复用。
 */
@Data
@TableName("sys_dict")
public class SysDict {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** CITY / SERVICE_TYPE / CANCEL_REASON / ABNORMAL_TYPE / REVIEW_TAG / CREDIT_RULE */
    private String dictType;
    private String itemValue;
    private String itemLabel;
    private Integer sort;
    /** 1 启用 0 停用（停用的不下发，§8.2） */
    private Integer enabled;
    /** 扩展字段 JSON，键按 dictType 约定（§8.2 的 extra 关键字段列） */
    private String extra;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
