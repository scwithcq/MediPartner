package com.dsc.medipartner.module.ai;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.module.ai.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ChatModelFactory 纯单测：provider 路由、Key 判定、Embedding 可用性（不发真实网络请求）。
 */
class ChatModelFactoryTest {

    private AiProperties props(String provider) {
        AiProperties props = new AiProperties();
        props.setProvider(provider);
        return props;
    }

    @Test
    void deepseekRoutesAndDisablesEmbedding() {
        AiProperties props = props("deepseek");
        props.getDeepseek().setApiKey("sk-test");
        ChatModelFactory factory = new ChatModelFactory(props);

        assertThat(factory.providerName()).isEqualTo("deepseek");
        assertThat(factory.hasKey()).isTrue();
        // DeepSeek 官方平台无 Embedding 接口，向量检索必须降级
        assertThat(factory.embeddingAvailable()).isFalse();
        assertThat(factory.embeddingModelName()).isEqualTo("none");
        assertThat(factory.chatModel()).isInstanceOf(OpenAiChatModel.class);
        assertThatThrownBy(factory::embeddingModel).isInstanceOf(BizException.class);
    }

    @Test
    void deepseekWithoutKeyDegrades() {
        ChatModelFactory factory = new ChatModelFactory(props("deepseek"));
        assertThat(factory.hasKey()).isFalse();
        assertThat(factory.embeddingAvailable()).isFalse();
        assertThatThrownBy(factory::chatModel).isInstanceOf(BizException.class);
    }

    @Test
    void qwenKeepsEmbeddingAvailable() {
        AiProperties props = props("qwen");
        props.getQwen().setApiKey("sk-qwen");
        ChatModelFactory factory = new ChatModelFactory(props);

        assertThat(factory.providerName()).isEqualTo("qwen");
        assertThat(factory.embeddingAvailable()).isTrue();
        assertThat(factory.embeddingModelName()).isEqualTo("text-embedding-v4");
        assertThat(factory.chatModel()).isInstanceOf(OpenAiChatModel.class);
        assertThat(factory.embeddingModel()).isNotNull();
    }

    @Test
    void zhipuUsesNativeModule() {
        AiProperties props = props("zhipu");
        props.getZhipu().setApiKey("sk-zhipu");
        ChatModelFactory factory = new ChatModelFactory(props);

        assertThat(factory.providerName()).isEqualTo("zhipu");
        assertThat(factory.embeddingAvailable()).isTrue();
        assertThat(factory.chatModel()).isInstanceOf(ZhiPuAiChatModel.class);
    }

    @Test
    void unknownProviderFallsBackToQwen() {
        AiProperties props = props("whatever");
        props.getQwen().setApiKey("sk-qwen");
        ChatModelFactory factory = new ChatModelFactory(props);
        assertThat(factory.providerName()).isEqualTo("qwen");
    }
}
