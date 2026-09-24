package com.dsc.medipartner.module.knowledge.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsc.medipartner.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_slice")
public class KnowledgeSlice extends BaseEntity {

    private Long docId;
    private Integer seq;
    private String content;
    /** 向量库文档 ID（与切片 ID 字符串一致），未向量化为 null */
    private String vectorId;
    /** KnowledgeSliceStatus.name() */
    private String status;
}
