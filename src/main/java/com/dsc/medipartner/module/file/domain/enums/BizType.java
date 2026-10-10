package com.dsc.medipartner.module.file.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Set;

/**
 * 上传业务类型（契约 §8.1 / 附录 B.9）：决定允许的扩展名与大小上限。
 * 私有类型（WORKER_CERT/KNOWLEDGE_DOC）的签名下载地址属阶段 1.5 完整契约，当前与公开类型同路径存储。
 */
@Getter
@AllArgsConstructor
public enum BizType {

    AVATAR(Set.of("jpg", "jpeg", "png", "webp"), 10, false),
    ORDER_TRACE(Set.of("jpg", "jpeg", "png", "webp"), 10, false),
    ORDER_VOUCHER(Set.of("jpg", "jpeg", "png", "webp"), 10, false),
    REVIEW_IMAGE(Set.of("jpg", "jpeg", "png", "webp"), 10, false),
    WORKER_CERT(Set.of("jpg", "jpeg", "png", "webp", "pdf"), 20, true),
    KNOWLEDGE_DOC(Set.of("pdf", "md", "txt"), 20, true);

    /** 允许的扩展名（小写，不含点） */
    private final Set<String> allowedExtensions;
    /** 大小上限（MB） */
    private final int maxSizeMb;
    /** 是否私有文件 */
    private final boolean privateFile;

    public static BizType of(String name) {
        for (BizType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }
}
