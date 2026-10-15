package com.dsc.medipartner.module.chat.domain;
import com.baomidou.mybatisplus.annotation.TableName;
import com.dsc.medipartner.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper = true) @TableName("chat_conversation")
public class ChatConversation extends BaseEntity { private Long orderId; private String orderNo; private Long userId; private Long workerId; private java.time.LocalDateTime acceptedAt; private Long userReadId; private Long workerReadId; private java.time.LocalDateTime purgedAt; }
