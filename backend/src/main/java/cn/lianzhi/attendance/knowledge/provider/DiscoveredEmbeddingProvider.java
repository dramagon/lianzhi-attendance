package cn.lianzhi.attendance.knowledge.provider;

import cn.lianzhi.attendance.knowledge.retrieval.VectorMath;
import com.alibaba.nacos.api.naming.NamingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Feign;
import feign.Headers;
import feign.Param;
import feign.Request;
import feign.RequestLine;
import feign.Response;
import feign.Retryer;

import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Discovers an embedding-only worker. Application authorization stays in the caller. */
public final class DiscoveredEmbeddingProvider implements EmbeddingProvider {
    public interface WorkerClient {
        @RequestLine("POST " + EmbeddingWorker.PATH)
        @Headers({"Content-Type: application/json", "Authorization: Bearer {token}"})
        Response embed(URI endpoint, @Param("token") String token, String body);
    }

    private final NamingService registry;
    private final String group;
    private final String model;
    private final String revision;
    private final String token;
    private final ObjectMapper json;
    private final WorkerClient worker = Feign.builder()
            .options(new Request.Options(2, TimeUnit.SECONDS, 25, TimeUnit.SECONDS, false))
            .retryer(Retryer.NEVER_RETRY).target(WorkerClient.class, "http://127.0.0.1");

    public DiscoveredEmbeddingProvider(NamingService registry, String group, String model,
                                       String revision, String token, ObjectMapper json) {
        EmbeddingRegistry.validateToken(token);
        if (model.isBlank()) throw new IllegalArgumentException("Embedding model is required");
        this.registry = registry;
        this.group = group;
        this.model = model;
        this.revision = revision;
        this.token = token;
        this.json = json;
    }

    @Override
    public ProviderAvailability availability() {
        return ProviderAvailability.AVAILABLE;
    }

    @Override
    public ProviderResult<List<Embedding>> embed(List<String> texts) {
        if (!EmbeddingWorker.validTexts(texts)) return failure("向量输入无效或超限");
        try {
            var instance = registry.selectInstances(EmbeddingRegistry.SERVICE, group, true).stream()
                    .filter(candidate -> model.equals(candidate.getMetadata().get("model"))
                            && revision.equals(candidate.getMetadata().get("revision")))
                    .findAny();
            if (instance.isEmpty()) return failure("没有匹配的向量服务实例");
            var host = instance.get();
            URI endpoint = new URI("http", null, host.getIp(), host.getPort(), null, null, null);
            String request = json.writeValueAsString(new EmbeddingWorker.Request(model, texts));
            try (Response response = worker.embed(endpoint, token, request)) {
                if (response.status() != 200 || response.body() == null)
                    return failure("向量服务返回失败状态");
                byte[] bytes = response.body().asInputStream().readNBytes(EmbeddingWorker.MAX_RESPONSE + 1);
                if (bytes.length > EmbeddingWorker.MAX_RESPONSE) return failure("向量响应超限");
                var result = json.readValue(bytes, EmbeddingWorker.Result.class);
                if (!model.equals(result.model()) || result.embeddings().size() != texts.size())
                    return failure("向量输出模型或数量不匹配");
                int dimension = result.embeddings().get(0).values().size();
                for (var vector : result.embeddings()) {
                    if (dimension > 4096 || dimension != vector.values().size())
                        return failure("向量输出维度无效");
                    VectorMath.unit(vector);
                }
                return new ProviderResult<>(ProviderResult.State.SUCCESS, "向量生成完成",
                        List.copyOf(result.embeddings()));
            }
        } catch (Exception e) {
            return failure("向量发现或调用失败");
        }
    }

    private static ProviderResult<List<Embedding>> failure(String message) {
        return new ProviderResult<>(ProviderResult.State.FAILURE, message, null);
    }
}
