package com.dsc.medipartner.module.file.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上传结果（契约 §8.1 FileVO 的阶段 3 精简版）。
 * fileId 依赖 sys_file 表（阶段 1.5 V7），当前不下发；sha256 照常返回。
 */
@Data
public class FileVO {

    /** 相对路径 /files/<bizType>/<yyyyMMdd>/<uuid>.<ext>，前端拼接服务地址访问 */
    private String url;
    private Boolean privateFile;
    private String sha256;
    private Long size;
    private String contentType;
    private LocalDateTime createdAt;
}
