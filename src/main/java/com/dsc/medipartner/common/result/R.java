package com.dsc.medipartner.common.result;

import lombok.Data;
import org.slf4j.MDC;

@Data
public class R<T> {

    private int code;
    private String message;
    private T data;
    private String traceId;
    private long timestamp;

    public static <T> R<T> ok() {
        return build(ErrorCode.SUCCESS, null);
    }

    public static <T> R<T> ok(T data) {
        return build(ErrorCode.SUCCESS, data);
    }

    public static <T> R<T> fail(ErrorCode errorCode) {
        return build(errorCode, null);
    }

    public static <T> R<T> fail(int code, String message) {
        R<T> r = new R<>();
        r.code = code;
        r.message = message;
        r.traceId = MDC.get("traceId");
        r.timestamp = System.currentTimeMillis();
        return r;
    }

    private static <T> R<T> build(ErrorCode errorCode, T data) {
        R<T> r = new R<>();
        r.code = errorCode.getCode();
        r.message = errorCode.getMessage();
        r.data = data;
        r.traceId = MDC.get("traceId");
        r.timestamp = System.currentTimeMillis();
        return r;
    }
}
