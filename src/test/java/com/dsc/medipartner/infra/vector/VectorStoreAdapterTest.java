package com.dsc.medipartner.infra.vector;

import com.dsc.medipartner.infra.vector.VectorStoreAdapter.VectorDoc;
import com.dsc.medipartner.infra.vector.VectorStoreAdapter.VectorHit;
import com.dsc.medipartner.module.ai.ChatModelFactory;
import com.dsc.medipartner.module.ai.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VectorStoreAdapter 纯单测：用假 EmbeddingModel（无需真实 API Key）验证
 * 不可用降级、增删查、文件持久化重载与按 embedding 模型名隔离。
 */
class VectorStoreAdapterTest {

    @TempDir
    Path tempDir;

    /** 确定性字符袋向量：共享字符越多余弦越大，保证同词查询为正相似度 */
    static class FakeEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<String> texts = request.getInstructions();
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                embeddings.add(new Embedding(vectorOf(texts.get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return vectorOf(document.getText());
        }

        private float[] vectorOf(String text) {
            float[] v = new float[64];
            if (text == null) {
                return v;
            }
            for (char c : text.toCharArray()) {
                int idx = Math.floorMod((int) c, 32);
                v[idx] += 1.0f;
                v[idx + 32] += 0.5f;
            }
            float norm = 0;
            for (float x : v) {
                norm += x * x;
            }
            norm = (float) Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < v.length; i++) {
                    v[i] /= norm;
                }
            }
            return v;
        }
    }

    /** 覆盖向量相关三个方法，绕开真实 API Key 校验 */
    static class TestChatModelFactory extends ChatModelFactory {

        boolean available = true;
        String modelName = "fake-embed-v1";
        private final FakeEmbeddingModel embedding = new FakeEmbeddingModel();

        TestChatModelFactory() {
            super(new AiProperties());
        }

        @Override
        public boolean embeddingAvailable() {
            return available;
        }

        @Override
        public EmbeddingModel embeddingModel() {
            return embedding;
        }

        @Override
        public String embeddingModelName() {
            return modelName;
        }
    }

    @Test
    void unavailableAdapterIsInert() {
        TestChatModelFactory factory = new TestChatModelFactory();
        factory.available = false;
        VectorStoreAdapter adapter = new VectorStoreAdapter(factory, tempDir.toString());

        assertThat(adapter.ready()).isFalse();
        adapter.addBatch(List.of(new VectorDoc("1", "胸痛应优先前往心血管内科就诊。", "d1")));
        assertThat(adapter.hasPersistedData()).isFalse();
        assertThat(adapter.search("胸痛", 3)).isEmpty();
    }

    @Test
    void addSearchRemoveRoundTrip() {
        VectorStoreAdapter adapter = new VectorStoreAdapter(new TestChatModelFactory(), tempDir.toString());
        assertThat(adapter.ready()).isTrue();

        adapter.addBatch(List.of(
                new VectorDoc("1", "胸痛应优先前往心血管内科就诊。", "d1"),
                new VectorDoc("2", "咳嗽发热建议挂呼吸内科。", "d1"),
                new VectorDoc("3", "腹痛腹泻可考虑消化内科。", "d2")));
        assertThat(adapter.hasPersistedData()).isTrue();

        List<VectorHit> hits = adapter.search("胸痛", 2);
        assertThat(hits).isNotEmpty().hasSizeLessThanOrEqualTo(2);
        assertThat(hits).extracting(VectorHit::id).isSubsetOf("1", "2", "3");
        assertThat(hits).allSatisfy(hit -> assertThat(hit.score()).isNotNull());

        adapter.removeBySliceIds(List.of("1"));
        assertThat(adapter.search("胸痛", 5)).extracting(VectorHit::id).doesNotContain("1");
    }

    @Test
    void persistsAcrossInstances() {
        TestChatModelFactory factory = new TestChatModelFactory();
        VectorStoreAdapter first = new VectorStoreAdapter(factory, tempDir.toString());
        first.addBatch(List.of(new VectorDoc("1", "胸痛应优先前往心血管内科就诊。", "d1")));

        VectorStoreAdapter reloaded = new VectorStoreAdapter(factory, tempDir.toString());
        assertThat(reloaded.hasPersistedData()).isTrue();
        assertThat(reloaded.search("胸痛", 3)).extracting(VectorHit::id).contains("1");
    }

    @Test
    void isolatesVectorsByEmbeddingModelName() {
        TestChatModelFactory factory = new TestChatModelFactory();
        VectorStoreAdapter adapter = new VectorStoreAdapter(factory, tempDir.toString());
        adapter.addBatch(List.of(new VectorDoc("1", "胸痛应优先前往心血管内科就诊。", "d1")));
        assertThat(adapter.search("胸痛", 3)).isNotEmpty();

        // 换 embedding 模型后旧向量被 filterExpression 隔离，需全量重建
        factory.modelName = "fake-embed-v2";
        assertThat(adapter.search("胸痛", 3)).isEmpty();
    }
}
