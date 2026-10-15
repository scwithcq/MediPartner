package com.dsc.medipartner.module.chat.controller;
import com.dsc.medipartner.common.result.R; import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.module.chat.service.OrderChatService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
@RestController @RequestMapping("/api/admin/chat")
public class AdminChatController {
 private final OrderChatService service;
 public AdminChatController(OrderChatService service) { this.service=service; }
 @GetMapping("/{orderNo}") public R<?> inspect(@PathVariable String orderNo,@RequestParam String appealNo,@RequestParam String reason,@RequestParam(required=false) Long beforeId) {
  return R.ok(service.inspect(UserContext.userId(),orderNo,appealNo,reason,beforeId));
 }
 @GetMapping("/{orderNo}/attachments/{key}") public ResponseEntity<byte[]> media(@PathVariable String orderNo,@PathVariable String key,@RequestParam String appealNo,@RequestParam String reason) {
  var media=service.inspectMedia(UserContext.userId(),orderNo,appealNo,reason,key);
  return ResponseEntity.ok().contentType(MediaType.parseMediaType(media.contentType())).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(media.bytes());
 }
}
