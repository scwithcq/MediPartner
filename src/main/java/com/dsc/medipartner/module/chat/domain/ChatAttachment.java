package com.dsc.medipartner.module.chat.domain;
import com.baomidou.mybatisplus.annotation.TableName;
import com.dsc.medipartner.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper = true) @TableName("chat_attachment")
public class ChatAttachment extends BaseEntity { private String attachmentKey; private Long conversationId; private Long uploaderId; private String storageName; private String contentType; private Long messageId; }
