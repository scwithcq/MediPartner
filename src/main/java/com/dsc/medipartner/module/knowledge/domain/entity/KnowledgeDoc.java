package com.dsc.medipartner.module.knowledge.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsc.medipartner.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_doc")
public class KnowledgeDoc extends BaseEntity {

    private String title;
    private String fileUrl;
    /** UPLOAD 后台上传 / SEED 内置语料 */
    private String source;
    private Integer version;
    /** KnowledgeDocStatus.name() */
    private String status;
    private LocalDateTime publishTime;
    private String remark;
}
