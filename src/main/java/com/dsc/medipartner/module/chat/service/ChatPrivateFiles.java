package com.dsc.medipartner.module.chat.service;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO; import java.nio.file.*; import java.io.*; import java.util.UUID;
@Component
public class ChatPrivateFiles {
 private final Path root;
 public record Stored(String name,String contentType) {}
 public ChatPrivateFiles(@Value("${medi.chat.private-dir:./data/chat-private}") String privateDir,
    @Value("${medi.file.base-dir:./data/files}") String publicDir) {
  root=Path.of(privateDir).toAbsolutePath().normalize();
  if(root.startsWith(Path.of(publicDir).toAbsolutePath().normalize())) throw new IllegalArgumentException("聊天图片目录不能位于公开文件目录内");
 }
 public Stored store(MultipartFile file) {
  if(file==null || file.isEmpty()) throw new BizException(ErrorCode.FILE_EMPTY);
  if(file.getSize()>5*1024*1024) throw new BizException(ErrorCode.FILE_SIZE_EXCEED,"聊天图片不能超过5MB");
  try(var input=ImageIO.createImageInputStream(file.getInputStream())) {
   var readers=ImageIO.getImageReaders(input);
   if(!readers.hasNext()) throw new BizException(ErrorCode.FILE_TYPE_NOT_SUPPORT,"聊天仅支持JPG/PNG图片");
   var reader=readers.next();
   try {
    String format=reader.getFormatName().toLowerCase();
    if(!format.equals("png") && !format.equals("jpeg") && !format.equals("jpg")) throw new BizException(ErrorCode.FILE_TYPE_NOT_SUPPORT);
    reader.setInput(input);
    if((long)reader.getWidth(0)*reader.getHeight(0)>12000000) throw new BizException(ErrorCode.FILE_SIZE_EXCEED,"图片分辨率过大，请压缩后发送");
    var picture=reader.read(0); String ext=format.equals("png") ? "png" : "jpg";
    String name=UUID.randomUUID().toString()+"."+ext;
    Files.createDirectories(root);
    // 重新编码，不沿用EXIF、GPS或原始文件名。
    if(!ImageIO.write(picture,ext,path(name).toFile())) throw new IOException("image encoder unavailable");
    if(Files.size(path(name))>10*1024*1024) { Files.deleteIfExists(path(name)); throw new BizException(ErrorCode.FILE_SIZE_EXCEED); }
    return new Stored(name,ext.equals("png") ? "image/png" : "image/jpeg");
   } finally { reader.dispose(); }
  } catch(IOException e) { throw new BizException(ErrorCode.FILE_STORE_FAILED,"聊天图片处理失败"); }
 }
 public byte[] read(String name) { try { return Files.readAllBytes(path(name)); } catch(IOException e) { throw new BizException(ErrorCode.FILE_STORE_FAILED,"图片已清理或不可用"); } }
 public void delete(String name) { try { Files.deleteIfExists(path(name)); } catch(IOException e) { throw new BizException(ErrorCode.FILE_STORE_FAILED,"聊天图片清理失败"); } }
 private Path path(String name) {
  if(name==null || !name.matches("[a-f0-9-]{36}\\.(png|jpg)")) throw new BizException(ErrorCode.FORBIDDEN);
  Path path=root.resolve(name).normalize(); if(!path.startsWith(root)) throw new BizException(ErrorCode.FORBIDDEN); return path;
 }
}
