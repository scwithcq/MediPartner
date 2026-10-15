package com.dsc.medipartner.module.chat.domain;
import com.baomidou.mybatisplus.annotation.TableName;
import com.dsc.medipartner.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper = true) @TableName("chat_message")
public class ChatMessage extends BaseEntity { private Long conversationId; private Long senderId; private String clientId; private String messageType; private String content; private String attachmentId; }
