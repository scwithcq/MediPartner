package com.dsc.medipartner.common.util;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 水印时间校验（契约 §8.1/§7.12/§7.14）：与服务器时间偏差超过 5 分钟返回 8004。
 * 以服务端时间为基准，防止客户端改系统时间伪造水印。
 */
public final class WatermarkTimes {

    private static final long MAX_DEVIATION_MINUTES = 5;

    private WatermarkTimes() {
    }

    public static void validate(LocalDateTime watermarkTime) {
        validate(watermarkTime, LocalDateTime.now());
    }

    static void validate(LocalDateTime watermarkTime, LocalDateTime serverNow) {
        if (watermarkTime == null) {
            return;
        }
        long minutes = Math.abs(Duration.between(serverNow, watermarkTime).toMinutes());
        if (minutes > MAX_DEVIATION_MINUTES) {
            throw new BizException(ErrorCode.WATERMARK_TIME_INVALID);
        }
    }
}
