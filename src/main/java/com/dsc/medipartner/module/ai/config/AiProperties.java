package com.dsc.medipartner.module.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 能力配置（medi.ai.*）。API Key 只从环境变量注入，禁止入库入代码。
 */
@Data
@ConfigurationProperties(prefix = "medi.ai")
public class AiProperties {

    /** 模型厂商：deepseek（默认，DeepSeek 官方 OpenAI 兼容模式）/ qwen（DashScope）/ zhipu（智谱原生） */
    private String provider = "deepseek";

    /** 免责声明文案，随 AiAssistVO 下发，前端必须展示 */
    private String disclaimer = "以上推荐由 AI 生成，仅供就医参考，不构成医疗诊断或治疗建议；症状紧急请立即拨打 120 或前往就近医院急诊。";

    /** RAG 检索条数 */
    private int topK = 3;

    /** 同一工单最大重新生成次数 */
    private int maxRegenerate = 5;

    private Qwen qwen = new Qwen();
    private Zhipu zhipu = new Zhipu();
    private Deepseek deepseek = new Deepseek();

    /**
     * DeepSeek 官方平台（api.deepseek.com，OpenAI 兼容模式）。
     * 该平台无 Embedding 接口，向量检索自动降级为关键词打分。
     */
    @Data
    public static class Deepseek {
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "";
        private String chatModel = "deepseek-flash";
        private Double temperature = 0.3;
    }

    @Data
    public static class Qwen {
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode";
        private String apiKey = "";
        private String chatModel = "qwen-plus";
        private String embeddingModel = "text-embedding-v4";
        private Integer embeddingDimensions = 1024;
        private Double temperature = 0.3;
    }

    @Data
    public static class Zhipu {
        private String baseUrl = "https://open.bigmodel.cn/api/paas";
        private String apiKey = "";
        private String chatModel = "glm-4-flash";
        private String embeddingModel = "embedding-2";
        private Double temperature = 0.3;
    }
}
