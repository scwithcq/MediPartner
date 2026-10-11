package com.dsc.medipartner.module.job.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 定时任务阈值配置（契约 §7.2 / §11 Q5）。
 *
 * <p><b>为什么统一用「分钟」而不是业务文档里的「小时」</b>：
 * 契约把阈值描述为 24h / 48h / 12h，但课设演示与联调不可能真等 48 小时。
 * 若配置项只接受小时，演示时想改成「1 分钟」就无从下手（要么改代码、要么等一天）。
 * 因此配置单位一律取分钟：业务默认值按小时换算（48h = 2880min），
 * 需要演示时把 2880 改成 1 即可，<b>无需改代码、无需重新打包</b>。
 *
 * <p>所有任务均<b>复用状态机已有事件</b>（{@code TIMEOUT_CANCEL} / {@code AUTO_CONFIRM} /
 * {@code MARK_ABNORMAL}），不新增流转逻辑（§7.2 明确要求）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "medi.job")
public class JobProperties {

    /** 总开关：压测或排查时可临时关闭全部定时任务，避免后台任务干扰观测 */
    private boolean enabled = true;

    // ---------------- 扫描节奏 ----------------

    /**
     * 定时任务扫描间隔（秒）。
     *
     * <p>默认 60 秒：阈值最小的任务（留痕超时 30 分钟）也远大于该间隔，
     * 不会漏扫。演示时把阈值调到 1 分钟，本项保持 60 亦可在 1 分钟内生效；
     * 若希望更快看到效果，可把它一并调小（如 10）。
     */
    private int scanIntervalSeconds = 60;

    /**
     * 单次扫描每类任务的批量上限。
     *
     * <p>限流的意义：积压了大量超时订单时，一次性处理几千单会长时间占用连接与事务，
     * 可能拖垮正常请求。分批处理让系统保持响应，剩余部分下次扫描继续。
     */
    private int batchSize = 200;

    // ---------------- 阈值（分钟） ----------------

    /** 超过该时长无人接单 → 自动取消（默认 24h，事件 TIMEOUT_CANCEL） */
    private long timeoutCancelMinutes = 24 * 60;

    /** 服务结束超过该时长用户仍未确认 → 自动确认完成（默认 48h，事件 AUTO_CONFIRM） */
    private long autoConfirmMinutes = 48 * 60;

    /** 服务中超过该时长仍未结束 → 标记异常（默认 12h，事件 MARK_ABNORMAL） */
    private long abnormalMinutes = 12 * 60;

    /**
     * 服务开始后超过该时长仍无任何留痕 → 告警并扣信用分（默认 45 分钟）。
     *
     * <p>契约 §7.2 的区间是「30~60min」，取中值 45 作为默认。
     * 单独惩罚理由：留痕是陪诊服务可追溯的唯一凭证，缺失会让纠纷无法仲裁。
     */
    private long traceTimeoutMinutes = 45;

    /**
     * 留痕超时是否扣信用分（默认 true，每次扣 {@code TRACE_TIMEOUT(-2)}）。
     *
     * <p>提供开关是因为：扣分属不可逆的惩罚动作，若阈值配错（例如设成 1 分钟）
     * 会批量误扣。排查期间可先只告警不扣分，确认阈值合理后再打开。
     */
    private boolean traceTimeoutPenalty = true;

    /**
     * 同一订单的留痕超时惩罚是否只扣一次。
     *
     * <p>默认 true。若不限制，每轮扫描（默认 60 秒一次）都会对该订单再扣 2 分，
     * 一小时就能把信用分扣穿到 0——这是典型的「定时任务重复执行造成资损」。
     * 幂等由 credit_change_log 的 (biz_no, type) 唯一约束与本开关共同保证。
     */
    private boolean tracePenaltyOncePerOrder = true;

    /**
     * 处理失败后的退避时长（分钟，默认 10）。
     *
     * <p>存在理由：某笔订单若因数据问题（如钱包金额与流水不一致）反复失败，
     * 不退避就会在每个扫描周期重复报错——实测每 20 秒一次、永不停歇，
     * 既刷爆日志又持续占用数据库，且掩盖了真正需要人工介入的问题。
     * 退避让日志保持可读，也给运维留出处理窗口。
     */
    private long failureBackoffMinutes = 10;
}
