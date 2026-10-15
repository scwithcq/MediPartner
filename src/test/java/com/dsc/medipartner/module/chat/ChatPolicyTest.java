package com.dsc.medipartner.module.chat;

import com.dsc.medipartner.module.chat.service.ChatPolicy;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class ChatPolicyTest {
 @Test void completedBoundariesAndAppealRetention() {
  var end = LocalDateTime.of(2026, 1, 1, 12, 0);
  var order = new ServiceOrder(); order.setOrderStatus(50); order.setConfirmedAt(end);
  assertThat(ChatPolicy.of(order, end.plusDays(7).minusNanos(1), false).canSend()).isTrue();
  assertThat(ChatPolicy.of(order, end.plusDays(7), false).canSend()).isFalse();
  assertThat(ChatPolicy.of(order, end.plusDays(90).minusNanos(1), false).expired()).isFalse();
  assertThat(ChatPolicy.of(order, end.plusDays(90), false).expired()).isTrue();
  var appealed = ChatPolicy.of(order, end.plusDays(91), true);
  assertThat(appealed.expired()).isFalse(); assertThat(appealed.canSend()).isFalse();
  assertThat(appealed.retainUntil()).isNull();
 }
 @Test void cancellationAndActiveService() {
  var now = LocalDateTime.of(2026, 1, 1, 12, 0);
  var order = new ServiceOrder(); order.setOrderStatus(60); order.setUpdatedAt(now);
  assertThat(ChatPolicy.of(order, now, false).canSend()).isFalse();
  assertThat(ChatPolicy.of(order, now.plusDays(90), false).expired()).isTrue();
  for (int status : new int[]{20, 30, 40, 70}) {
   order.setOrderStatus(status);
   assertThat(ChatPolicy.of(order, now.plusDays(180), false).canSend()).isTrue();
   assertThat(ChatPolicy.of(order, now.plusDays(180), false).expired()).isFalse();
  }
 }
}
