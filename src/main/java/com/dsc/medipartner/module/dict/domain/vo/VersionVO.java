package com.dsc.medipartner.module.dict.domain.vo;

import lombok.Data;

/**
 * §8.4 版本与契约兼容检查响应。
 *
 * <p>{@code apiCompatVersion} 是本接口的真正价值（契约 §8.4 备注 1）：
 * 小程序版本由微信托管、后端无法左右，但「接口契约版本」由本项目掌控。
 * 前端把上次成功启动时的值存本地，发现服务端更大即说明期间有字段变更，
 * 触发一次全量刷新（清缓存、重拉 §8.2 字典与 §8.3 配置），避免旧缓存渲染新契约。
 */
@Data
public class VersionVO {

    /** 最新小程序版本号，如 1.0.0 */
    private String latestVersion;

    /** 最低可用版本，低于此版本前端弹强制更新 */
    private String minSupportVersion;

    /** 是否强制更新 */
    private Boolean forceUpdate;

    /** 更新说明文案 */
    private String updateTips;

    /**
     * 接口契约版本号，与《接口设计文档》§12 变更记录的递增序号一致。
     *
     * <p>每次执行破坏性变更时必须同步 +1——这是「文档—代码—客户端」三方对齐的锚点。
     */
    private Integer apiCompatVersion;
}
