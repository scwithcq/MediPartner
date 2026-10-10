package com.dsc.medipartner.module.dict.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 客户端配置下发（契约 §8.3）。
 *
 * <p>权限为<b>匿名</b>：小程序 onLaunch 就要读，登录页也需要。读不到时返回代码内置默认值，
 * <b>保证小程序任何情况下都能启动</b>（不能因为配置接口失败而白屏）。
 */
@Data
public class ClientConfigVO {

    /** 轮询间隔（秒），前端不得写死（§1.9） */
    private Map<String, Object> pollInterval;
    /** 功能开关；未上线能力为 false，前端隐藏入口 */
    private Map<String, Object> featureFlags;
    /** 微信订阅消息模板 ID；阶段 5 前为空串占位 */
    private Map<String, Object> subscribeTemplateIds;
    /** 上传限制 */
    private Map<String, Object> uploadLimit;
    /** 平台客服电话 */
    private String servicePhone;
    /** 协议地址（相对路径） */
    private Map<String, Object> agreements;
    /** 服务器当前时间，前端校准水印时间偏差用 */
    private LocalDateTime serverTime;
}
