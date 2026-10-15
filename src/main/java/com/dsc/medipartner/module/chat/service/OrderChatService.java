package com.dsc.medipartner.module.chat.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dsc.medipartner.common.cache.CacheService;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.chat.domain.*;
import com.dsc.medipartner.module.chat.domain.ChatViews.*;
import com.dsc.medipartner.module.chat.mapper.*;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.time.*; import java.util.*; import java.util.regex.Pattern;

@Service @Transactional
public class OrderChatService {
 private final ChatOrderMapper orders; private final ChatConversationMapper conversations;
 private final ChatMessageMapper messages; private final ChatAttachmentMapper attachments;
 private final UserService users; private final ChatPrivateFiles files; private final CacheService cache;
 public OrderChatService(ChatOrderMapper orders,ChatConversationMapper conversations,ChatMessageMapper messages,
    ChatAttachmentMapper attachments,UserService users,ChatPrivateFiles files,CacheService cache) {
  this.orders=orders; this.conversations=conversations; this.messages=messages; this.attachments=attachments;
  this.users=users; this.files=files; this.cache=cache;
 }
 record Context(ServiceOrder order,ChatConversation conversation,ChatPolicy.Policy policy) {}
 private Context context(Long userId,String orderNo) {
  ServiceOrder order=orders.lockOrder(orderNo);
  if(order==null) throw new BizException(ErrorCode.ORDER_NOT_FOUND);
  if(!Objects.equals(order.getUserId(),userId) && !Objects.equals(order.getWorkerId(),userId)) throw new BizException(ErrorCode.ORDER_NOT_BELONG_TO_USER);
  if(order.getWorkerId()==null || order.getOrderStatus()==10) throw new BizException(ErrorCode.ORDER_STATE_ILLEGAL,"接单后才能开启聊天");
  LocalDateTime accepted=order.getAcceptedAt()!=null ? order.getAcceptedAt() : order.getCreatedAt();
  var conversation=conversations.selectOne(new LambdaQueryWrapper<ChatConversation>().eq(ChatConversation::getOrderId,order.getId())
    .eq(ChatConversation::getWorkerId,order.getWorkerId()).eq(ChatConversation::getAcceptedAt,accepted));
  if(conversation==null) {
   conversation=new ChatConversation(); conversation.setOrderId(order.getId()); conversation.setOrderNo(orderNo);
   conversation.setUserId(order.getUserId()); conversation.setWorkerId(order.getWorkerId()); conversation.setAcceptedAt(accepted);
   conversation.setUserReadId(0L); conversation.setWorkerReadId(0L); conversations.insert(conversation);
  }
  var policy=ChatPolicy.of(order,LocalDateTime.now(),orders.activeAppeals(orderNo)>0);
  return new Context(order,conversation,policy);
 }
 private void readable(Context ctx) { if(ctx.policy().expired() || ctx.conversation().getPurgedAt()!=null) throw new BizException(ErrorCode.PARAM_ERROR,"聊天记录已超过保留期限并清理"); }
 private void writable(Context ctx) { readable(ctx); if(!ctx.policy().canSend()) throw new BizException(ErrorCode.ORDER_STATE_ILLEGAL,ctx.policy().tip()); }
 public Conversation detail(Long userId,String orderNo) { return view(userId,context(userId,orderNo)); }
 private Conversation view(Long userId,Context ctx) {
  var c=ctx.conversation(); boolean owner=Objects.equals(c.getUserId(),userId);
  var peer=users.getById(owner ? c.getWorkerId() : c.getUserId());
  // 不输出真实姓名、手机号、用户ID或患者订单信息。
  var who=new Peer(peer.getNickname()!=null ? peer.getNickname() : owner ? "陪诊师" : "医伴用户",peer.getAvatar(),owner ? "WORKER" : "USER",owner ? String.valueOf(c.getWorkerId()) : null);
  long read=owner ? c.getUserReadId() : c.getWorkerReadId();
  long unread=ctx.policy().expired() ? 0 : messages.selectCount(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getConversationId,c.getId()).ne(ChatMessage::getSenderId,userId).gt(ChatMessage::getId,read));
  return new Conversation(String.valueOf(c.getId()),c.getOrderNo(),OrderState.of(ctx.order().getOrderStatus()).name(),who,
    ctx.policy().canSend() && c.getPurgedAt()==null,ctx.policy().tip(),String.valueOf(unread),ctx.policy().sendUntil(),ctx.policy().retainUntil(),3);
 }
 public List<Conversation> inbox(Long userId) {
  List<Conversation> result=new ArrayList<>();
  for(var c:orders.inbox(userId)) { var ctx=context(userId,c.getOrderNo()); if(!ctx.policy().expired() && c.getPurgedAt()==null) result.add(view(userId,ctx)); }
  return result;
 }
 public Messages history(Long userId,String orderNo,Long beforeId,Long afterId) {
  var ctx=context(userId,orderNo); readable(ctx);
  if(beforeId!=null && afterId!=null) throw new BizException(ErrorCode.PARAM_ERROR,"不能同时传beforeId和afterId");
  return page(userId,ctx.conversation().getId(),beforeId,afterId);
 }
 private Messages page(Long userId,Long conversationId,Long beforeId,Long afterId) {
  var query=new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getConversationId,conversationId)
   .lt(beforeId!=null,ChatMessage::getId,beforeId).gt(afterId!=null,ChatMessage::getId,afterId);
  if(afterId!=null) query.orderByAsc(ChatMessage::getId); else query.orderByDesc(ChatMessage::getId);
  List<ChatMessage> found=messages.selectList(query.last("LIMIT 51")); boolean more=found.size()>50;
  if(more) found=new ArrayList<>(found.subList(0,50));
  if(afterId==null) Collections.reverse(found);
  return new Messages(found.stream().map(m->message(userId,m)).toList(),more);
 }
 private Message message(Long userId,ChatMessage m) { return new Message(String.valueOf(m.getId()),m.getClientId(),Objects.equals(userId,m.getSenderId()),m.getMessageType(),m.getContent(),m.getAttachmentId(),m.getCreatedAt()); }
 public static boolean sensitive(String content) { return content!=null && Pattern.compile("(?<![0-9])(?:1[3-9][0-9]{9}|[0-9]{17}[0-9Xx]|[0-9]{16,19})(?![0-9])").matcher(content.replaceAll("[ -]", "")).find(); }
 public Message send(Long userId,String orderNo,SendChatRequest request) {
  var ctx=context(userId,orderNo); readable(ctx);
  var old=messages.selectOne(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getConversationId,ctx.conversation().getId()).eq(ChatMessage::getSenderId,userId).eq(ChatMessage::getClientId,request.getClientId()));
  if(old!=null) {
   if(!Objects.equals(old.getMessageType(),request.getMessageType()) || !Objects.equals(old.getContent(),request.getContent()==null?null:request.getContent().trim()) || !Objects.equals(old.getAttachmentId(),request.getAttachmentId())) throw new BizException(ErrorCode.PARAM_ERROR,"重试消息内容不一致");
   return message(userId,old);
  }
  writable(ctx);
  if("TEXT".equals(request.getMessageType())) {
   if(request.getContent()==null || request.getContent().isBlank() || request.getContent().length()>1000 || request.getAttachmentId()!=null) throw new BizException(ErrorCode.PARAM_ERROR,"文字消息需为1～1000字");
   if(sensitive(request.getContent()) && !request.isSensitiveConfirmed()) throw new BizException(ErrorCode.CHAT_SENSITIVE_CONFIRM);
  } else if(!"IMAGE".equals(request.getMessageType()) || request.getContent()!=null) throw new BizException(ErrorCode.PARAM_ERROR);
  ChatAttachment attachment=null;
  if("IMAGE".equals(request.getMessageType())) {
   attachment=attachment(request.getAttachmentId(),ctx);
   if(!Objects.equals(attachment.getUploaderId(),userId) || attachment.getMessageId()!=null) throw new BizException(ErrorCode.FORBIDDEN,"不能发送他人或已使用的图片");
  }
  var m=new ChatMessage(); m.setConversationId(ctx.conversation().getId()); m.setSenderId(userId); m.setClientId(request.getClientId());
  m.setMessageType(request.getMessageType()); m.setContent(request.getContent()==null?null:request.getContent().trim()); m.setAttachmentId(request.getAttachmentId()); messages.insert(m);
  if(attachment!=null) { attachment.setMessageId(m.getId()); attachments.updateById(attachment); }
  conversations.update(null,new LambdaUpdateWrapper<ChatConversation>().eq(ChatConversation::getId,ctx.conversation().getId()).set(ChatConversation::getUpdatedAt,LocalDateTime.now()));
  return message(userId,m);
 }
 public void read(Long userId,String orderNo,Long messageId) {
  var ctx=context(userId,orderNo); readable(ctx); var m=messages.selectById(messageId);
  if(m==null || !Objects.equals(m.getConversationId(),ctx.conversation().getId())) throw new BizException(ErrorCode.PARAM_ERROR,"消息不属于当前会话");
  boolean owner=Objects.equals(ctx.conversation().getUserId(),userId);
  conversations.update(null,new LambdaUpdateWrapper<ChatConversation>().eq(ChatConversation::getId,ctx.conversation().getId())
    .setSql((owner ? "user_read_id" : "worker_read_id")+"=GREATEST("+(owner ? "user_read_id" : "worker_read_id")+","+messageId+")"));
 }
 public Map<String,String> upload(Long userId,String orderNo,MultipartFile file) {
  var ctx=context(userId,orderNo); writable(ctx); var stored=files.store(file);
  var a=new ChatAttachment(); a.setConversationId(ctx.conversation().getId()); a.setUploaderId(userId); a.setAttachmentKey(UUID.randomUUID().toString()); a.setStorageName(stored.name()); a.setContentType(stored.contentType());
  try { attachments.insert(a); } catch(RuntimeException e) { files.delete(stored.name()); throw e; }
  return Map.of("attachmentId",a.getAttachmentKey());
 }
 private ChatAttachment attachment(String key,Context ctx) {
  if(key==null) throw new BizException(ErrorCode.PARAM_ERROR,"图片参数缺失");
  var a=attachments.selectOne(new LambdaQueryWrapper<ChatAttachment>().eq(ChatAttachment::getAttachmentKey,key));
  if(a==null || !Objects.equals(a.getConversationId(),ctx.conversation().getId())) throw new BizException(ErrorCode.FORBIDDEN,"图片不属于当前会话"); return a;
 }
 public MediaTicket ticket(Long userId,String orderNo,String key) {
  var ctx=context(userId,orderNo); readable(ctx); var a=attachment(key,ctx);
  if(a.getMessageId()==null && !Objects.equals(a.getUploaderId(),userId)) throw new BizException(ErrorCode.FORBIDDEN);
  String ticket=UUID.randomUUID().toString(); cache.set("medi:chat-media:"+ticket,userId+":"+key,Duration.ofMinutes(5));
  return new MediaTicket("/api/chat/"+orderNo+"/attachments/"+key+"?ticket="+ticket,300);
 }
 public record Media(byte[] bytes,String contentType) {}
 public Media media(Long userId,String orderNo,String key,String ticket) {
  if(ticket==null || !Objects.equals(cache.get("medi:chat-media:"+ticket),userId+":"+key)) throw new BizException(ErrorCode.FORBIDDEN,"图片访问凭据已失效，请重新加载");
  var ctx=context(userId,orderNo); readable(ctx); var a=attachment(key,ctx);
  if(a.getMessageId()==null && !Objects.equals(a.getUploaderId(),userId)) throw new BizException(ErrorCode.FORBIDDEN);
  return new Media(files.read(a.getStorageName()),a.getContentType());
 }
 public Messages inspect(Long adminId,String orderNo,String appealNo,String reason,Long beforeId) {
  if(reason==null || reason.isBlank() || reason.length()>200 || appealNo==null || orders.appealMatches(orderNo,appealNo)==0) throw new BizException(ErrorCode.FORBIDDEN,"需提供关联申诉单和查阅理由");
  var order=orders.lockOrder(orderNo); if(order==null) throw new BizException(ErrorCode.ORDER_NOT_FOUND);
  var ctx=context(order.getUserId(),orderNo); readable(ctx);
  orders.audit(adminId,orderNo,appealNo,reason);
  return page(adminId,ctx.conversation().getId(),beforeId,null);
 }
 public void purge(ChatConversation c) {
  var ctx=context(c.getUserId(),c.getOrderNo()); if(!ctx.policy().expired()) return;
  for(var a:attachments.selectList(new LambdaQueryWrapper<ChatAttachment>().eq(ChatAttachment::getConversationId,c.getId()))) files.delete(a.getStorageName());
  attachments.delete(new LambdaQueryWrapper<ChatAttachment>().eq(ChatAttachment::getConversationId,c.getId()));
  messages.delete(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getConversationId,c.getId()));
  conversations.update(null,new LambdaUpdateWrapper<ChatConversation>().eq(ChatConversation::getId,c.getId()).set(ChatConversation::getPurgedAt,LocalDateTime.now()).set(ChatConversation::getUserReadId,0).set(ChatConversation::getWorkerReadId,0));
 }
 public Media inspectMedia(Long adminId,String orderNo,String appealNo,String reason,String key) {
  if(reason==null || reason.isBlank() || reason.length()>200 || appealNo==null || orders.appealMatches(orderNo,appealNo)==0) throw new BizException(ErrorCode.FORBIDDEN,"需提供关联申诉单和查阅理由");
  var order=orders.lockOrder(orderNo); if(order==null) throw new BizException(ErrorCode.ORDER_NOT_FOUND);
  var ctx=context(order.getUserId(),orderNo); readable(ctx); var a=attachment(key,ctx);
  if(a.getMessageId()==null) throw new BizException(ErrorCode.FORBIDDEN,"未发送的图片不属于申诉证据");
  orders.audit(adminId,orderNo,appealNo,reason);
  return new Media(files.read(a.getStorageName()),a.getContentType());
 }
 public void purgeOrphans() {
  var pending=attachments.selectList(new LambdaQueryWrapper<ChatAttachment>().isNull(ChatAttachment::getMessageId).lt(ChatAttachment::getCreatedAt,LocalDateTime.now().minusDays(1)).last("LIMIT 100"));
  for(var a:pending) {
   var c=conversations.selectById(a.getConversationId()); if(c==null) continue;
   orders.lockOrder(c.getOrderNo());
   var fresh=attachments.selectById(a.getId());
   if(fresh!=null && fresh.getMessageId()==null) { files.delete(fresh.getStorageName()); attachments.deleteById(fresh.getId()); }
  }
 }
}
