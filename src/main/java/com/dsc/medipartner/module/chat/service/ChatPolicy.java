package com.dsc.medipartner.module.chat.service;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import java.time.LocalDateTime;
public final class ChatPolicy {
 public record Policy(boolean canSend, boolean expired, String tip, LocalDateTime sendUntil, LocalDateTime retainUntil) {}
 public static Policy of(ServiceOrder order, LocalDateTime now, boolean appealActive) {
  int status=order.getOrderStatus();
  LocalDateTime end=order.getConfirmedAt()!=null ? order.getConfirmedAt() : order.getUpdatedAt();
  if (status==50 || status==60) {
   if (end==null) return new Policy(false,false,"订单已结束，仅可查看记录",null,null);
   LocalDateTime retain=end.plusDays(90);
   boolean expired=!appealActive && !now.isBefore(retain);
   LocalDateTime sendUntil=status==50 ? end.plusDays(7) : end;
   boolean send=status==50 && now.isBefore(sendUntil) && !expired;
   return new Policy(send,expired,expired ? "聊天记录已超过保留期限" : status==60 ? "订单已取消，聊天只读" : send ? "服务完成后7天内仍可沟通，记录保留90天；申诉处理中延长保留" : "服务完成已超过7天，聊天只读",sendUntil,appealActive ? null : retain);
  }
  boolean send=status==20 || status==30 || status==40 || status==70;
  return new Policy(send,false,send ? "请勿发送身份证号、银行卡号等隐私信息。聊天内容不用于AI推荐。" : "等待陪诊师接单后可聊天",null,null);
 }
}
