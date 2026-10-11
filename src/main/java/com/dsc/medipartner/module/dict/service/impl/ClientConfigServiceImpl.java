package com.dsc.medipartner.module.dict.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsc.medipartner.module.dict.domain.entity.SysConfig;
import com.dsc.medipartner.module.dict.domain.vo.ClientConfigVO;
import com.dsc.medipartner.module.dict.domain.vo.VersionVO;
import com.dsc.medipartner.module.dict.mapper.SysConfigMapper;
import com.dsc.medipartner.module.dict.service.ClientConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端配置下发实现（契约 §8.3）。
 *
 * <p>三级兜底，确保小程序永不因配置缺失而白屏：
 * <ol>
 *   <li>DB 有该键且解析成功 -> 用 DB 值</li>
 *   <li>DB 无该键或解析失败 -> 用<b>代码内置默认值</b>（仅该项回退，不影响其它项）</li>
 *   <li>整个查询抛异常 -> 全量内置默认值</li>
 * </ol>
 */
@Slf4j
@Service
public class ClientConfigServiceImpl implements ClientConfigService {

    /** 内置默认值：与 V15 种子数据保持一致，DB 不可用时也能启动小程序 */
    private static final String DEFAULT_POLL_INTERVAL =
            "{\"hall\":10,\"report\":3,\"orderDetail\":15,\"workerOrderList\":15}";
    private static final String DEFAULT_FEATURE_FLAGS =
            "{\"aiAssist\":true,\"demandOrder\":true,\"workerApply\":false,\"review\":true,"
                    + "\"withdraw\":false,\"virtualPhone\":true,\"appendReview\":true}";
    private static final String DEFAULT_UPLOAD_LIMIT =
            "{\"imageMaxMb\":10,\"pdfMaxMb\":20,\"traceMaxCount\":20,"
                    + "\"reviewImageMaxCount\":3,\"avatarMaxMb\":5}";
    private static final String DEFAULT_AGREEMENTS =
            "{\"user\":\"/files/agreements/user.html\","
                    + "\"worker\":\"/files/agreements/worker.html\","
                    + "\"privacy\":\"/files/agreements/privacy.html\"}";
    private static final String DEFAULT_SERVICE_PHONE = "400-000-0000";

    /** 微信订阅消息模板 ID：DB 未配时为空串占位（§8.3 / §11 Q10） */
    private static final Map<String, Object> DEFAULT_SUBSCRIBE_TEMPLATE_IDS = new LinkedHashMap<>();

    /** §8.4 版本信息内置默认值（与 V18 种子一致） */
    private static final String DEFAULT_LATEST_VERSION = "1.0.0";
    private static final String DEFAULT_MIN_SUPPORT_VERSION = "1.0.0";
    private static final String DEFAULT_UPDATE_TIPS = "";
    /** 契约版本号：破坏性变更时 +1（§8.4 备注 2） */
    private static final int DEFAULT_API_COMPAT_VERSION = 1;

    static {
        DEFAULT_SUBSCRIBE_TEMPLATE_IDS.put("ORDER_ACCEPTED", "");
        DEFAULT_SUBSCRIBE_TEMPLATE_IDS.put("SERVICE_STARTED", "");
        DEFAULT_SUBSCRIBE_TEMPLATE_IDS.put("PENDING_CONFIRM", "");
        DEFAULT_SUBSCRIBE_TEMPLATE_IDS.put("AUDIT_RESULT", "");
        DEFAULT_SUBSCRIBE_TEMPLATE_IDS.put("APPEAL_RESULT", "");
    }

    private final SysConfigMapper configMapper;
    private final ObjectMapper objectMapper;

    public ClientConfigServiceImpl(SysConfigMapper configMapper, ObjectMapper objectMapper) {
        this.configMapper = configMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ClientConfigVO getClientConfig() {
        Map<String, String> stored = loadAll();

        ClientConfigVO vo = new ClientConfigVO();
        vo.setPollInterval(readJsonMap(stored, "pollInterval", DEFAULT_POLL_INTERVAL));
        vo.setFeatureFlags(readJsonMap(stored, "featureFlags", DEFAULT_FEATURE_FLAGS));
        vo.setUploadLimit(readJsonMap(stored, "uploadLimit", DEFAULT_UPLOAD_LIMIT));
        vo.setAgreements(readJsonMap(stored, "agreements", DEFAULT_AGREEMENTS));
        vo.setServicePhone(readString(stored, "servicePhone", DEFAULT_SERVICE_PHONE));
        // 阶段 5：模板 ID 改为从 sys_config 读取（V18 已落种子）。
        // 未填时仍为空串占位，服务侧只记日志不发送（§11 Q10），不影响启动。
        vo.setSubscribeTemplateIds(readTemplateIds(stored));
        vo.setServerTime(LocalDateTime.now());
        return vo;
    }

    /**
     * §8.3 订阅消息模板 ID：以 DB 配置为准，缺失的键用空串补齐。
     *
     * <p>用「DB 覆盖默认值」而不是「DB 整体替换」：模板 ID 是逐项申请的
     * （契约 §11 Q10 列了 5 个），运维可能只填了其中 2 个，
     * 此时其余 3 个必须仍然下发空串，否则前端读不到键会报 undefined。
     */
    private Map<String, Object> readTemplateIds(Map<String, String> stored) {
        Map<String, Object> result = new LinkedHashMap<>(DEFAULT_SUBSCRIBE_TEMPLATE_IDS);
        Map<String, Object> fromDb = readJsonMap(stored, "subscribeTemplateIds", "{}");
        for (Map.Entry<String, Object> entry : fromDb.entrySet()) {
            if (DEFAULT_SUBSCRIBE_TEMPLATE_IDS.containsKey(entry.getKey()) && entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /**
     * §8.4 版本与契约兼容检查。
     *
     * <p>与 §8.3 同样「读不到就用内置默认值」：本接口在 onLaunch 并行调用，
     * 抛异常会让小程序启动链断掉，而版本信息缺失远没有启动失败严重。
     */
    @Override
    public VersionVO getVersion() {
        Map<String, String> stored = loadAll();

        VersionVO vo = new VersionVO();
        vo.setLatestVersion(readString(stored, "latestVersion", DEFAULT_LATEST_VERSION));
        vo.setMinSupportVersion(readString(stored, "minSupportVersion", DEFAULT_MIN_SUPPORT_VERSION));
        vo.setUpdateTips(readString(stored, "updateTips", DEFAULT_UPDATE_TIPS));
        // 强制更新判定：最低支持版本高于最新版本属配置错误，
        // 此时不强制（false）——宁可让旧版本继续用，也不要因配置失误把所有用户挡在门外
        String latest = vo.getLatestVersion();
        String min = vo.getMinSupportVersion();
        vo.setForceUpdate(compareVersion(latest, min) > 0);

        String compat = stored.get("apiCompatVersion");
        vo.setApiCompatVersion(parseCompatVersion(compat));
        return vo;
    }

    /**
     * 解析契约版本号；非法或缺失时回落默认值。
     *
     * <p>版本号解析失败不能让接口报错——前端把它当「是否需要清缓存」的判据，
     * 返回默认值最多导致多刷一次缓存，返回 500 则会阻断启动。
     */
    private Integer parseCompatVersion(String raw) {
        if (!StringUtils.hasText(raw)) {
            return DEFAULT_API_COMPAT_VERSION;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("[config] apiCompatVersion 非法，回落默认值 {}: {}", DEFAULT_API_COMPAT_VERSION, raw);
            return DEFAULT_API_COMPAT_VERSION;
        }
    }

    /**
     * 比较 {@code a.b.c} 形式版本号。
     *
     * <p>刻意不用字符串比较：那样 "1.10.0" 会被判为小于 "1.9.0"，导致本该强制更新的用户被放过。
     *
     * @return 正数表示 a 大于 b
     */
    private int compareVersion(String a, String b) {
        if (!StringUtils.hasText(a) || !StringUtils.hasText(b)) {
            return 0;
        }
        String[] left = a.trim().split("\\.");
        String[] right = b.trim().split("\\.");
        int len = Math.max(left.length, right.length);
        for (int i = 0; i < len; i++) {
            int l = i < left.length ? parseSegment(left[i]) : 0;
            int r = i < right.length ? parseSegment(right[i]) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    /** 版本段解析：非数字段按 0 处理（如 "1.0.0-beta" 的 beta 段） */
    private int parseSegment(String segment) {
        try {
            return Integer.parseInt(segment.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 一次性读出全部配置；DB 异常时返回空表，让所有项走内置默认值 */
    private Map<String, String> loadAll() {
        try {
            Map<String, String> values = new LinkedHashMap<>();
            for (SysConfig config : configMapper.selectList(new LambdaQueryWrapper<>())) {
                if (StringUtils.hasText(config.getConfigKey())) {
                    values.put(config.getConfigKey().trim(), config.getConfigValue());
                }
            }
            return values;
        } catch (Exception e) {
            log.warn("[config] 读取 sys_config 失败，全量使用内置默认值: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 取 JSON 对象配置；缺失或解析失败回落内置默认值，只影响该项 */
    private Map<String, Object> readJsonMap(Map<String, String> stored, String key, String defaultValue) {
        String raw = stored.get(key);
        if (StringUtils.hasText(raw)) {
            try {
                return objectMapper.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {
                });
            } catch (Exception e) {
                log.warn("[config] {} 解析失败，回落内置默认值: {}", key, e.getMessage());
            }
        }
        try {
            return objectMapper.readValue(defaultValue, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            // 内置默认值是我们自己写的常量，解析失败属编程错误
            log.error("[config] 内置默认值 {} 解析失败", key, e);
            return new LinkedHashMap<>();
        }
    }

    private String readString(Map<String, String> stored, String key, String defaultValue) {
        String raw = stored.get(key);
        return StringUtils.hasText(raw) ? raw : defaultValue;
    }
}
