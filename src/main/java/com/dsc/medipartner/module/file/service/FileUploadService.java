package com.dsc.medipartner.module.file.service;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.util.WatermarkTimes;
import com.dsc.medipartner.infra.oss.OssService;
import com.dsc.medipartner.module.file.domain.enums.BizType;
import com.dsc.medipartner.module.file.domain.vo.FileVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 统一文件上传（契约 §8.1 的阶段 3 最小实现）：
 * bizType 白名单校验扩展名与大小 -> sha256 -> OssService 落盘 -> 返回 /files/ 相对路径。
 * 阶段 1.5 再补 sys_file 落库、sha256 秒传与私有文件签名地址；本接口不做业务校验（契约 §8.1 备注 4）。
 */
@Slf4j
@Service
public class FileUploadService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OssService ossService;

    public FileUploadService(OssService ossService) {
        this.ossService = ossService;
    }

    public FileVO upload(MultipartFile file, String bizTypeName, String watermarkTime) {
        BizType bizType = BizType.of(bizTypeName);
        if (bizType == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "bizType 缺失或非法");
        }
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.FILE_EMPTY);
        }
        if (file.getSize() > bizType.getMaxSizeMb() * 1024L * 1024L) {
            throw new BizException(ErrorCode.FILE_SIZE_EXCEED);
        }
        String originalFilename = file.getOriginalFilename();
        String ext = extensionOf(originalFilename);
        if (!bizType.getAllowedExtensions().contains(ext)) {
            throw new BizException(ErrorCode.FILE_TYPE_NOT_SUPPORT);
        }
        if (watermarkTime != null && !watermarkTime.isBlank()) {
            WatermarkTimes.validate(parseWatermark(watermarkTime));
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("[file] 读取上传流失败 bizType={}", bizType, e);
            throw new BizException(ErrorCode.FILE_STORE_FAILED);
        }
        String stored = ossService.store(bizType.name(), originalFilename, new ByteArrayInputStream(bytes));

        FileVO vo = new FileVO();
        vo.setUrl("/files/" + stored);
        vo.setPrivateFile(bizType.isPrivateFile());
        vo.setSha256(sha256(bytes));
        vo.setSize(file.getSize());
        vo.setContentType(file.getContentType());
        vo.setCreatedAt(LocalDateTime.now());
        return vo;
    }

    private LocalDateTime parseWatermark(String text) {
        try {
            return LocalDateTime.parse(text.trim(), TS);
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "watermarkTime 格式须为 yyyy-MM-dd HH:mm:ss");
        }
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }
    }
}
