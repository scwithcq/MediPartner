package com.dsc.medipartner.integration;
import com.dsc.medipartner.common.security.TokenService;
import com.dsc.medipartner.module.chat.domain.*;
import com.dsc.medipartner.module.chat.mapper.*;
import com.dsc.medipartner.module.chat.service.*;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.mapper.ServiceOrderMapper;
import com.dsc.medipartner.module.user.service.UserService;
import com.dsc.medipartner.module.user.domain.enums.RoleEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*; import java.time.*; import java.util.*; import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("integration") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("integration") @Transactional
class OrderChatIntegrationTest {
 static final Path PRIVATE=Path.of(System.getProperty("java.io.tmpdir"),"medi-chat-test-"+UUID.randomUUID());
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r) { r.add("medi.chat.private-dir",()->PRIVATE.toString()); r.add("medi.chat.cleanup-interval-ms",()->86400000); }
 @Autowired com.dsc.medipartner.common.cache.CacheService cache;
 @Autowired OrderChatService chats; @Autowired UserService users; @Autowired ServiceOrderMapper orders;
 @Autowired ChatConversationMapper conversations; @Autowired ChatAttachmentMapper attachments;
 @Autowired TokenService tokens; @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired ObjectMapper json;
 Long userId,workerId,outsiderId; String orderNo; String userToken,workerToken,otherToken;
 @BeforeEach void setup() {
  var u=users.createUser("chat-user-"+UUID.randomUUID(),"用户昵称",1); userId=u.getId();
  var w=users.createUser("chat-worker-"+UUID.randomUUID(),"陪诊师昵称",3); workerId=w.getId();
  var o=users.createUser("chat-other-"+UUID.randomUUID(),"无关用户",1); outsiderId=o.getId();
  userToken=tokens.issueAccessToken(userId,RoleEnum.authoritiesOf(1)); workerToken=tokens.issueAccessToken(workerId,RoleEnum.authoritiesOf(3)); otherToken=tokens.issueAccessToken(outsiderId,RoleEnum.authoritiesOf(1));
  var order=new ServiceOrder(); orderNo="CT"+UUID.randomUUID().toString().replace("-","").substring(0,25);
  order.setOrderNo(orderNo); order.setUserId(userId); order.setWorkerId(workerId); order.setOrderStatus(20);
  order.setAmount(new BigDecimal("100.00")); order.setServiceType("ACCOMPANY"); order.setVersion(0);
  order.setAcceptedAt(LocalDateTime.now().withNano(0)); order.setServiceTime(LocalDateTime.now().plusDays(1)); orders.insert(order);
 }
 SendChatRequest text(String value) { var r=new SendChatRequest(); r.setClientId(UUID.randomUUID().toString()); r.setMessageType("TEXT"); r.setContent(value); return r; }
 String url(String suffix) { return "/api/chat/"+orderNo+suffix; }
 @Test void ownershipUnreadRetryAndPrivacy() throws Exception {
  mvc.perform(get(url("")).header("Authorization","Bearer "+otherToken)).andExpect(jsonPath("$.code").value(3008));
  mvc.perform(get("/api/admin/chat/"+orderNo).param("appealNo","AP123").param("reason","测试").header("Authorization","Bearer "+otherToken)).andExpect(jsonPath("$.code").value(1003));
  mvc.perform(get(url("/messages"))).andExpect(jsonPath("$.code").value(1002));
  var request=text("明天九点门诊大厅见");
  var message=chats.send(userId,orderNo,request);
  assertThat(chats.send(userId,orderNo,request).id()).isEqualTo(message.id());
  assertThat(chats.history(userId,orderNo,null,null).records()).hasSize(1);
  assertThat(chats.detail(workerId,orderNo).unreadCount()).isEqualTo("1");
  assertThat(chats.detail(userId,orderNo).unreadCount()).isEqualTo("0");
  chats.read(workerId,orderNo,Long.valueOf(message.id())); assertThat(chats.detail(workerId,orderNo).unreadCount()).isEqualTo("0");
  String serialized=json.writeValueAsString(chats.detail(workerId,orderNo));
  assertThat(serialized).doesNotContain("phone","openid","symptomDesc","contactPhone","password");
  assertThat(chats.detail(userId,orderNo).peer().workerUserId()).isEqualTo(workerId.toString());
  var sensitive=text("手机号13800138000");
  mvc.perform(post(url("/messages")).header("Authorization","Bearer "+userToken).contentType("application/json").content(json.writeValueAsString(sensitive))).andExpect(jsonPath("$.code").value(7001));
  sensitive.setSensitiveConfirmed(true); chats.send(userId,orderNo,sensitive);
  var peer=chats.send(workerId,orderNo,text("收到"));
  chats.read(workerId,orderNo,Long.valueOf(peer.id())); chats.read(workerId,orderNo,Long.valueOf(message.id()));
  assertThat(chats.detail(workerId,orderNo).unreadCount()).isEqualTo("0");
 }
 @Test void reassignSeparatesConversationAndCursors() {
  var old=chats.send(userId,orderNo,text("上一位陪诊师的会话")); String conversation=chats.detail(userId,orderNo).conversationId();
  jdbc.update("UPDATE service_order SET worker_id=?,accepted_at=DATE_ADD(accepted_at,INTERVAL 1 SECOND) WHERE order_no=?",outsiderId,orderNo);
  assertThat(chats.detail(userId,orderNo).conversationId()).isNotEqualTo(conversation);
  assertThat(chats.history(outsiderId,orderNo,null,null).records()).isEmpty();
  assertThatThrownBy(()->chats.history(workerId,orderNo,null,null)).hasMessageContaining("无权");
  assertThatThrownBy(()->chats.read(outsiderId,orderNo,Long.valueOf(old.id()))).hasMessageContaining("不属于");
 }
 @Test void rejectsChatBeforeAcceptAndPaginatesWithoutLoss() {
  jdbc.update("UPDATE service_order SET order_status=10,worker_id=NULL WHERE order_no=?",orderNo);
  assertThatThrownBy(()->chats.detail(userId,orderNo)).hasMessageContaining("接单后");
  jdbc.update("UPDATE service_order SET order_status=20,worker_id=? WHERE order_no=?",workerId,orderNo);
  for(int i=0;i<55;i++) chats.send(userId,orderNo,text("消息"+i));
  var latest=chats.history(workerId,orderNo,null,null); assertThat(latest.records()).hasSize(50); assertThat(latest.hasMore()).isTrue();
  var older=chats.history(workerId,orderNo,Long.valueOf(latest.records().get(0).id()),null); assertThat(older.records()).hasSize(5);
  var after=chats.history(workerId,orderNo,null,Long.valueOf(older.records().get(4).id())); assertThat(after.records()).hasSize(50);
 }
 @Test void privateImagesRequireSameOrderUserAndUnexpiredTicket() throws Exception {
  byte[] picture=png();
  var upload=mvc.perform(multipart(url("/attachments")).file(new MockMultipartFile("file","hospital.png","image/png",picture)).header("Authorization","Bearer "+userToken))
    .andExpect(jsonPath("$.code").value(0)).andReturn();
  String key=json.readTree(upload.getResponse().getContentAsString()).path("data").path("attachmentId").asText();
  assertThatThrownBy(()->chats.ticket(workerId,orderNo,key)).hasMessageContaining("无权限");
  var send=new SendChatRequest(); send.setClientId(UUID.randomUUID().toString()); send.setMessageType("IMAGE"); send.setAttachmentId(key);
  chats.send(userId,orderNo,send);
  var ticket=chats.ticket(workerId,orderNo,key);
  mvc.perform(get(ticket.url()).header("Authorization","Bearer "+workerToken)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(content().contentType("image/png"));
  mvc.perform(get(ticket.url()).header("Authorization","Bearer "+otherToken)).andExpect(jsonPath("$.code").value(1003));
  mvc.perform(get(url("/attachments/"+key)+"?ticket=bad").header("Authorization","Bearer "+workerToken)).andExpect(jsonPath("$.code").value(1003));
  mvc.perform(get(ticket.url())).andExpect(jsonPath("$.code").value(1002));
  cache.delete("medi:chat-media:"+ticket.url().split("ticket=")[1]);
  mvc.perform(get(ticket.url()).header("Authorization","Bearer "+workerToken)).andExpect(jsonPath("$.code").value(1003));
  assertThatThrownBy(()->chats.upload(userId,orderNo,new MockMultipartFile("file","fake.jpg","image/jpeg","not-an-image".getBytes()))).hasMessageContaining("JPG/PNG");
  var other=new SendChatRequest(); other.setClientId(UUID.randomUUID().toString()); other.setMessageType("IMAGE"); other.setAttachmentId(key);
  assertThatThrownBy(()->chats.send(workerId,orderNo,other)).hasMessageContaining("不能发送");
 }
 @Test void completedReadOnlyRetentionAndAppealAudit() {
  var m=chats.send(userId,orderNo,text("服务结束"));
  jdbc.update("UPDATE service_order SET order_status=50,confirmed_at=DATE_SUB(NOW(),INTERVAL 6 DAY) WHERE order_no=?",orderNo);
  assertThat(chats.detail(userId,orderNo).canSend()).isTrue();
  jdbc.update("UPDATE service_order SET confirmed_at=DATE_SUB(NOW(),INTERVAL 8 DAY) WHERE order_no=?",orderNo);
  assertThat(chats.detail(userId,orderNo).canSend()).isFalse(); assertThatThrownBy(()->chats.send(userId,orderNo,text("新消息"))).hasMessageContaining("只读");
  jdbc.update("UPDATE service_order SET confirmed_at=DATE_SUB(NOW(),INTERVAL 91 DAY) WHERE order_no=?",orderNo);
  assertThatThrownBy(()->chats.history(userId,orderNo,null,null)).hasMessageContaining("保留期限");
  long appealId=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(); String appeal="AP"+appealId;
  jdbc.update("INSERT INTO appeal_ticket (id,appeal_no,order_no,applicant_id,appeal_type,reason,status) VALUES (?,?,?,?,'SERVICE_QUALITY',?,'PENDING')",appealId,appeal,orderNo,userId,"测试申诉");
  assertThat(chats.history(userId,orderNo,null,null).records()).hasSize(1);
  assertThatThrownBy(()->chats.inspect(outsiderId,orderNo,"invalid","核实申诉",null)).hasMessageContaining("关联申诉");
  assertThat(chats.inspect(outsiderId,orderNo,appeal,"核实申诉",null).records()).hasSize(1);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_access_audit WHERE order_no=?",Integer.class,orderNo)).isEqualTo(1);
  jdbc.update("UPDATE appeal_ticket SET status='CLOSED' WHERE id=?",appealId);
  var c=conversations.selectById(Long.valueOf(chats.detail(userId,orderNo).conversationId())); chats.purge(c);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE conversation_id=?",Integer.class,c.getId())).isZero();
 }
 @Test void cancelledConversationIsImmediatelyReadOnly() {
  chats.send(userId,orderNo,text("取消前消息"));
  jdbc.update("UPDATE service_order SET order_status=60 WHERE order_no=?",orderNo);
  assertThat(chats.detail(userId,orderNo).canSend()).isFalse(); assertThat(chats.history(userId,orderNo,null,null).records()).hasSize(1);
  assertThatThrownBy(()->chats.send(userId,orderNo,text("取消后消息"))).hasMessageContaining("已取消");
 }
 static byte[] png() throws Exception { var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB); var out=new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image,"png",out); return out.toByteArray(); }
 @AfterAll static void cleanup() throws Exception { if(Files.exists(PRIVATE)) try(var paths=Files.walk(PRIVATE)) { for(var path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
}
