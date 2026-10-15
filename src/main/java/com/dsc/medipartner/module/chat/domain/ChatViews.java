package com.dsc.medipartner.module.chat.domain;
import java.time.LocalDateTime; import java.util.List;
public final class ChatViews {
 public record Peer(String nickname, String avatar, String role, String workerUserId) {}
 public record Conversation(String conversationId, String orderNo, String orderStatus, Peer peer,
     boolean canSend, String tip, String unreadCount, LocalDateTime sendUntil, LocalDateTime retainUntil, int pollSeconds) {}
 public record Message(String id, String clientId, boolean mine, String messageType, String content,
     String attachmentId, LocalDateTime createdAt) {}
 public record Messages(List<Message> records, boolean hasMore) {}
 public record ReadRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp="[0-9]{1,20}") String messageId) {}
 public record MediaTicket(String url, int expiresIn) {}
}
