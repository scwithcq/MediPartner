package com.dsc.medipartner.module.chat.controller;
import com.dsc.medipartner.common.ratelimit.RateLimit;
import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.module.chat.domain.*;
import com.dsc.medipartner.module.chat.service.OrderChatService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
@RestController @RequestMapping("/api/chat")
public class OrderChatController {
 private final OrderChatService service;
 public OrderChatController(OrderChatService service) { this.service=service; }
 @GetMapping("/inbox") public R<?> inbox() { return R.ok(service.inbox(UserContext.userId())); }
 @GetMapping("/{orderNo}") public R<?> detail(@PathVariable String orderNo) { return R.ok(service.detail(UserContext.userId(),orderNo)); }
 @GetMapping("/{orderNo}/messages") public R<?> history(@PathVariable String orderNo,@RequestParam(required=false) Long beforeId,@RequestParam(required=false) Long afterId) {
  return R.ok(service.history(UserContext.userId(),orderNo,beforeId,afterId));
 }
 @PostMapping("/{orderNo}/messages") @RateLimit(name="chatSend",limit=60,windowSeconds=60)
 public R<?> send(@PathVariable String orderNo,@Valid @RequestBody SendChatRequest request) { return R.ok(service.send(UserContext.userId(),orderNo,request)); }
 @PostMapping("/{orderNo}/read") public R<?> read(@PathVariable String orderNo,@Valid @RequestBody ChatViews.ReadRequest request) {
  service.read(UserContext.userId(),orderNo,Long.valueOf(request.messageId())); return R.ok();
 }
 @PostMapping("/{orderNo}/attachments") @RateLimit(name="chatImageUpload",limit=20,windowSeconds=60)
 public R<?> upload(@PathVariable String orderNo,@RequestParam("file") MultipartFile file) { return R.ok(service.upload(UserContext.userId(),orderNo,file)); }
 @PostMapping("/{orderNo}/attachments/{key}/ticket") public R<?> ticket(@PathVariable String orderNo,@PathVariable String key) { return R.ok(service.ticket(UserContext.userId(),orderNo,key)); }
 @GetMapping("/{orderNo}/attachments/{key}") public ResponseEntity<byte[]> media(@PathVariable String orderNo,@PathVariable String key,@RequestParam String ticket) {
  var media=service.media(UserContext.userId(),orderNo,key,ticket);
  return ResponseEntity.ok().contentType(MediaType.parseMediaType(media.contentType())).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(media.bytes());
 }
}
