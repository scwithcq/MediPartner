package com.dsc.medipartner.module.ai;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.ai.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.ai.zhipuai.ZhiPuAiEmbeddingModel;
import org.springframework.ai.zhipuai.ZhiPuAiEmbeddingOptions;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 大模型 Provider 策略：按 medi.ai.provider 运行时选择 deepseek/qwen/zhipu。
 * deepseek 走 DeepSeek 官方平台 OpenAI 兼容模式，qwen 走 DashScope OpenAI 兼容模式（均用 spring-ai-openai），
 * zhipu 走原生模块（spring-ai-zhipuai）。
 * DeepSeek 官方平台无 Embedding 接口，embeddingAvailable()=false，检索降级为关键词打分。
 * 未配置 API Key 时 hasKey()=false，上层降级为模板输出（degraded=true）。
 */
@Slf4j
@Component
public class ChatModelFactory {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 25_000;

    private final AiProperties props;
    private volatile ChatModel chatModel;
    private volatile EmbeddingModel embeddingModel;

    public ChatModelFactory(AiProperties props) {
        this.props = props;
    }

    public boolean isZhipu() {
        return "zhipu".equalsIgnoreCase(props.getProvider());
    }

    public boolean isDeepseek() {
        return "deepseek".equalsIgnoreCase(props.getProvider());
    }

    /** 契约中的 provider 字段：deepseek / qwen / zhipu */
    public String providerName() {
        if (isZhipu()) {
            return "zhipu";
        }
        return isDeepseek() ? "deepseek" : "qwen";
    }

    public String apiKey() {
        if (isZhipu()) {
            return props.getZhipu().getApiKey();
        }
        return isDeepseek() ? props.getDeepseek().getApiKey() : props.getQwen().getApiKey();
    }

    public boolean hasKey() {
        String key = apiKey();
        return key != null && !key.isBlank();
    }

    /** 向量能力是否可用（依赖 API Key 且平台提供 Embedding 接口；不可用时检索降级为关键词打分） */
    public boolean embeddingAvailable() {
        return hasKey() && !isDeepseek();
    }

    public String embeddingModelName() {
        if (isZhipu()) {
            return props.getZhipu().getEmbeddingModel();
        }
        return isDeepseek() ? "none" : props.getQwen().getEmbeddingModel();
    }

    public ChatModel chatModel() {
        if (!hasKey()) {
            throw new BizException(ErrorCode.LLM_CALL_FAILED, "未配置 AI 模型 API Key");
        }
        ChatModel local = chatModel;
        if (local == null) {
            synchronized (this) {
                if (chatModel == null) {
                    chatModel = buildChatModel();
                }
                local = chatModel;
            }
        }
        return local;
    }

    public EmbeddingModel embeddingModel() {
        if (!hasKey()) {
            throw new BizException(ErrorCode.LLM_CALL_FAILED, "未配置 AI 模型 API Key");
        }
        EmbeddingModel local = embeddingModel;
        if (local == null) {
            synchronized (this) {
                if (embeddingModel == null) {
                    embeddingModel = buildEmbeddingModel();
                }
                local = embeddingModel;
            }
        }
        return local;
    }

    private RestClient.Builder timedRestClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return RestClient.builder().requestFactory(factory);
    }

    private ChatModel buildChatModel() {
        if (isZhipu()) {
            AiProperties.Zhipu cfg = props.getZhipu();
            ZhiPuAiApi api = ZhiPuAiApi.builder()
                    .baseUrl(cfg.getBaseUrl())
                    .apiKey(cfg.getApiKey())
                    .restClientBuilder(timedRestClientBuilder())
                    .build();
            log.info("[AI] 初始化 zhipu ChatModel: {}", cfg.getChatModel());
            return new ZhiPuAiChatModel(api, ZhiPuAiChatOptions.builder()
                    .model(cfg.getChatModel())
                    .temperature(cfg.getTemperature())
                    .build());
        }
        if (isDeepseek()) {
            AiProperties.Deepseek cfg = props.getDeepseek();
            log.info("[AI] 初始化 deepseek(官方平台兼容模式) ChatModel: {}", cfg.getChatModel());
            return buildOpenAiCompatibleChatModel(cfg.getBaseUrl(), cfg.getApiKey(),
                    cfg.getChatModel(), cfg.getTemperature());
        }
        AiProperties.Qwen cfg = props.getQwen();
        log.info("[AI] 初始化 qwen(DashScope 兼容模式) ChatModel: {}", cfg.getChatModel());
        return buildOpenAiCompatibleChatModel(cfg.getBaseUrl(), cfg.getApiKey(),
                cfg.getChatModel(), cfg.getTemperature());
    }

    /** OpenAI 兼容模式通用构建（qwen / deepseek 共用），强制 JSON 输出 */
    private ChatModel buildOpenAiCompatibleChatModel(String baseUrl, String apiKey,
                                                     String model, Double temperature) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .completionsPath("/v1/chat/completions")
                .embeddingsPath("/v1/embeddings")
                .restClientBuilder(timedRestClientBuilder())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, null))
                        .build())
                .build();
    }

    private EmbeddingModel buildEmbeddingModel() {
        if (isDeepseek()) {
            throw new BizException(ErrorCode.LLM_CALL_FAILED, "DeepSeek 官方平台无 Embedding 接口，向量检索已降级为关键词打分");
        }
        if (isZhipu()) {
            AiProperties.Zhipu cfg = props.getZhipu();
            ZhiPuAiApi api = ZhiPuAiApi.builder()
                    .baseUrl(cfg.getBaseUrl())
                    .apiKey(cfg.getApiKey())
                    .restClientBuilder(timedRestClientBuilder())
                    .build();
            log.info("[AI] 初始化 zhipu EmbeddingModel: {}", cfg.getEmbeddingModel());
            return new ZhiPuAiEmbeddingModel(api, MetadataMode.EMBED,
                    ZhiPuAiEmbeddingOptions.builder().model(cfg.getEmbeddingModel()).build());
        }
        AiProperties.Qwen cfg = props.getQwen();
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(cfg.getBaseUrl())
                .apiKey(cfg.getApiKey())
                .completionsPath("/v1/chat/completions")
                .embeddingsPath("/v1/embeddings")
                .restClientBuilder(timedRestClientBuilder())
                .build();
        log.info("[AI] 初始化 qwen EmbeddingModel: {} dim={}", cfg.getEmbeddingModel(), cfg.getEmbeddingDimensions());
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, OpenAiEmbeddingOptions.builder()
                .model(cfg.getEmbeddingModel())
                .dimensions(cfg.getEmbeddingDimensions())
                .build());
    }
}
