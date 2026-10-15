package com.dsc.medipartner.module.chat.service;
import com.dsc.medipartner.module.chat.mapper.ChatOrderMapper;
import org.springframework.stereotype.Component; import org.springframework.scheduling.annotation.Scheduled;
import lombok.extern.slf4j.Slf4j;
@Component @Slf4j
public class ChatRetentionJob {
 private final ChatOrderMapper orders; private final OrderChatService service;
 public ChatRetentionJob(ChatOrderMapper orders,OrderChatService service) { this.orders=orders; this.service=service; }
 @Scheduled(fixedDelayString="${medi.chat.cleanup-interval-ms:3600000}",initialDelay=60000)
 public void clean() {
  for(var c:orders.expired()) try { service.purge(c); } catch(RuntimeException e) { log.warn("[chat] 过期会话清理失败 id={}",c.getId()); }
  try { service.purgeOrphans(); } catch(RuntimeException e) { log.warn("[chat] 未发送图片清理失败"); }
 }
}
