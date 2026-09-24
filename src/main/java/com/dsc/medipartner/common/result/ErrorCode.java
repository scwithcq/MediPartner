package com.dsc.medipartner.common.result;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    SUCCESS(0, "success"),
    PARAM_ERROR(1001, "参数校验失败"),
    UNAUTHORIZED(1002, "未登录或登录已过期"),
    FORBIDDEN(1003, "无权限访问"),
    TOKEN_EXPIRED(1004, "登录已过期，请重新登录"),
    REPEAT_REQUEST(1005, "请求过于频繁，请稍后再试"),
    USER_NOT_FOUND(2001, "用户不存在"),

    ORDER_NOT_FOUND(3001, "订单不存在"),
    ORDER_STATE_ILLEGAL(3002, "订单状态不允许该操作"),
    ORDER_ALREADY_ACCEPTED(3003, "手慢一步，该订单已被其他人抢走"),
    ORDER_ACCEPT_LIMIT(3004, "当前接单已达上限，请先完成现有服务"),
    CREDIT_NOT_ENOUGH(3005, "信用分不足，暂无法接单"),

    WALLET_BALANCE_NOT_ENOUGH(4001, "账户余额不足"),
    WALLET_TXN_DUPLICATE(4002, "幂等键重复"),
    WALLET_ACCOUNT_NOT_FOUND(4004, "钱包账户不存在"),
    WALLET_UPDATE_CONFLICT(4005, "钱包更新冲突，请重试"),

    TICKET_NOT_FOUND(5001, "工单不存在"),

    KNOWLEDGE_NOT_PUBLISHED(6001, "知识库尚未发布，AI 能力不可用"),
    LLM_CALL_FAILED(6002, "AI 服务暂不可用，请稍后重试"),
    DOC_PARSE_FAILED(6003, "文档解析失败"),
    DEMAND_CLOSED(6005, "该需求工单已关闭，请重新发起"),

    FILE_EMPTY(8001, "上传文件为空"),
    FILE_STORE_FAILED(8005, "文件存储失败，请重试"),

    SYSTEM_ERROR(9001, "系统异常，请稍后重试"),
    EXTERNAL_TIMEOUT(9002, "外部服务超时");

    private final int code;
    private final String message;
}
