package com.dsc.medipartner.module.chat.domain;
import lombok.Getter; import lombok.Setter;
import jakarta.validation.constraints.*;
@Getter @Setter
public class SendChatRequest {
 @NotBlank @Pattern(regexp="[A-Za-z0-9-]{8,64}") private String clientId;
 @NotBlank @Pattern(regexp="TEXT|IMAGE") private String messageType;
 @Size(max=1000) private String content;
 @Size(max=64) private String attachmentId;
 private boolean sensitiveConfirmed;
}
