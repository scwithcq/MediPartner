package com.dsc.medipartner.module.dict.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户端配置表（契约 §8.3；表由 V15 建）。
 * 这是灰度与降级开关的唯一入口：某能力线上异常，改配置即可让全量小程序隐藏入口，不必发版审核。
 */
@Data
@TableName("sys_config")
public class SysConfig {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String configKey;
    /** JSON 或标量文本 */
    private String configValue;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
