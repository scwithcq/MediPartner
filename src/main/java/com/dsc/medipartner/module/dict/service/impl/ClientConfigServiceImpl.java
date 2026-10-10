package com.dsc.medipartner.module.dict.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsc.medipartner.module.dict.domain.entity.SysConfig;
import com.dsc.medipartner.module.dict.domain.vo.ClientConfigVO;
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

    /** 微信订阅消息模板 ID：阶段 5 前为空串占位（§8.3） */
    private static final Map<String, Object> DEFAULT_SUBSCRIBE_TEMPLATE_IDS = new LinkedHashMap<>();

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
        vo.setSubscribeTemplateIds(new LinkedHashMap<>(DEFAULT_SUBSCRIBE_TEMPLATE_IDS));
        vo.setServerTime(LocalDateTime.now());
        return vo;
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
