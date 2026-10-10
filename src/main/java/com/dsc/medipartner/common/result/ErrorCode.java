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
    FEATURE_NOT_AVAILABLE(1006, "该功能尚未开放，敬请期待"),
    USER_NOT_FOUND(2001, "用户不存在"),
    WORKER_AUDIT_NOT_PASS(2003, "入驻审核未通过，暂不可接单"),

    ORDER_NOT_FOUND(3001, "订单不存在"),
    ORDER_STATE_ILLEGAL(3002, "订单状态不允许该操作"),
    ORDER_ALREADY_ACCEPTED(3003, "手慢一步，该订单已被其他人抢走"),
    ORDER_ACCEPT_LIMIT(3004, "当前接单已达上限，请先完成现有服务"),
    CREDIT_NOT_ENOUGH(3005, "信用分不足，暂无法接单"),
    SERVICE_TIME_ILLEGAL(3007, "服务时间不合法（需晚于当前时间且在可预约范围内）"),
    ORDER_NOT_BELONG_TO_USER(3008, "无权操作该订单"),
    PRICE_RULE_NOT_FOUND(3009, "该服务类型暂未开放定价"),
    PRICE_CHANGED(3010, "服务价格已更新，请重新确认"),

    WALLET_BALANCE_NOT_ENOUGH(4001, "账户余额不足"),
    WALLET_TXN_DUPLICATE(4002, "幂等键重复"),
    WALLET_ACCOUNT_NOT_FOUND(4004, "钱包账户不存在"),
    WALLET_UPDATE_CONFLICT(4005, "钱包更新冲突，请重试"),

    TICKET_NOT_FOUND(5001, "工单不存在"),
    REVIEW_NOT_ALLOWED(5003, "当前状态不可评价"),
    REVIEW_ALREADY_REPLIED(5004, "该评价已回复，不可重复回复"),
    REVIEW_APPEND_USED(5005, "追评机会已使用"),

    KNOWLEDGE_NOT_PUBLISHED(6001, "知识库尚未发布，AI 能力不可用"),
    LLM_CALL_FAILED(6002, "AI 服务暂不可用，请稍后重试"),
    DOC_PARSE_FAILED(6003, "文档解析失败"),
    REPORT_PROCESSING(6004, "AI 报告生成中，请稍后刷新"),
    DEMAND_CLOSED(6005, "该需求工单已关闭，请重新发起"),

    FILE_EMPTY(8001, "上传文件为空"),
    FILE_TYPE_NOT_SUPPORT(8002, "不支持的文件类型"),
    FILE_SIZE_EXCEED(8003, "文件大小超出限制"),
    WATERMARK_TIME_INVALID(8004, "水印时间异常，请重新拍摄"),
    FILE_STORE_FAILED(8005, "文件存储失败，请重试"),

    SYSTEM_ERROR(9001, "系统异常，请稍后重试"),
    EXTERNAL_TIMEOUT(9002, "外部服务超时");

    private final int code;
    private final String message;
}
