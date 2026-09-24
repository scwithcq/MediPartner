package com.dsc.medipartner.infra.oss;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * 本地目录存储实现：文件落在 ${medi.file.base-dir} 下，路径做了防目录穿越校验。
 */
@Slf4j
@Service
public class LocalOssServiceImpl implements OssService {

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final Path baseDir;

    public LocalOssServiceImpl(@Value("${medi.file.base-dir:./data/files}") String baseDir) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
    }

    @Override
    public String store(String bizType, String originalFilename, InputStream input) {
        if (input == null) {
            throw new BizException(ErrorCode.FILE_EMPTY);
        }
        String ext = extensionOf(originalFilename);
        String relative = bizType + "/" + LocalDate.now().format(DATE_DIR) + "/"
                + UUID.randomUUID().toString().replace("-", "") + ext;
        Path target = resolveSafely(relative);
        try {
            Files.createDirectories(target.getParent());
            long copied = Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            if (copied == 0) {
                Files.deleteIfExists(target);
                throw new BizException(ErrorCode.FILE_EMPTY);
            }
            return relative;
        } catch (IOException e) {
            log.error("文件存储失败: {}", relative, e);
            throw new BizException(ErrorCode.FILE_STORE_FAILED);
        }
    }

    @Override
    public byte[] read(String fileUrl) {
        Path target = resolveSafely(fileUrl);
        if (!Files.isRegularFile(target)) {
            throw new BizException(ErrorCode.FILE_STORE_FAILED, "文件不存在: " + fileUrl);
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            log.error("文件读取失败: {}", fileUrl, e);
            throw new BizException(ErrorCode.FILE_STORE_FAILED);
        }
    }

    @Override
    public boolean exists(String fileUrl) {
        try {
            return Files.isRegularFile(resolveSafely(fileUrl));
        } catch (BizException e) {
            return false;
        }
    }

    /** 归一化后必须仍位于 baseDir 内，防 ../ 穿越 */
    private Path resolveSafely(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文件路径不能为空");
        }
        Path resolved = baseDir.resolve(relative).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new BizException(ErrorCode.FORBIDDEN, "非法文件路径");
        }
        return resolved;
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot).toLowerCase(Locale.ROOT);
    }
}
