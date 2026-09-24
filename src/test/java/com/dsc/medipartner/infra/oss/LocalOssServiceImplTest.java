package com.dsc.medipartner.infra.oss;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地对象存储纯单测：读写回环、空文件 8001、目录穿越 1003、缺失文件 8005。
 */
class LocalOssServiceImplTest {

    @TempDir
    Path tempDir;

    private LocalOssServiceImpl newService() {
        return new LocalOssServiceImpl(tempDir.toString());
    }

    @Test
    void storeAndReadRoundTrip() {
        LocalOssServiceImpl oss = newService();
        String path = oss.store("KNOWLEDGE_DOC", "指南.MD",
                new ByteArrayInputStream("内容".getBytes(StandardCharsets.UTF_8)));
        assertThat(path).startsWith("KNOWLEDGE_DOC/").endsWith(".md");
        assertThat(oss.exists(path)).isTrue();
        assertThat(new String(oss.read(path), StandardCharsets.UTF_8)).isEqualTo("内容");
    }

    @Test
    void storeEmptyOrNullStreamThrowsFileEmpty() {
        LocalOssServiceImpl oss = newService();
        assertThatThrownBy(() -> oss.store("BIZ", "a.txt", new ByteArrayInputStream(new byte[0])))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FILE_EMPTY));
        assertThatThrownBy(() -> oss.store("BIZ", "a.txt", null))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FILE_EMPTY));
    }

    @Test
    void traversalPathRejectedWithForbidden() {
        LocalOssServiceImpl oss = newService();
        assertThatThrownBy(() -> oss.read("../../etc/passwd"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(oss.exists("../evil.txt")).isFalse();
    }

    @Test
    void readMissingFileThrowsStoreFailed() {
        LocalOssServiceImpl oss = newService();
        assertThatThrownBy(() -> oss.read("BIZ/20260921/none.txt"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FILE_STORE_FAILED));
    }
}
