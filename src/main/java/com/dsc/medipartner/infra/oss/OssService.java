package com.dsc.medipartner.infra.oss;

import java.io.InputStream;

/**
 * 对象存储抽象：默认本地目录实现，未来可替换 MinIO/OSS 而不动业务代码。
 */
public interface OssService {

    /**
     * 保存文件。
     *
     * @param bizType          业务类型（如 KNOWLEDGE_DOC），作为存储子目录
     * @param originalFilename 原始文件名（用于提取扩展名）
     * @param input            文件流，调用方负责关闭
     * @return 相对存储路径（即 file_url），如 KNOWLEDGE_DOC/20260921/uuid.pdf
     */
    String store(String bizType, String originalFilename, InputStream input);

    /**
     * 按存储路径读取文件内容。
     */
    byte[] read(String fileUrl);

    /**
     * 存储路径对应文件是否存在。
     */
    boolean exists(String fileUrl);
}
