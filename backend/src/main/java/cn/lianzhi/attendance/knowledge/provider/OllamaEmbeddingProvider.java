package cn.lianzhi.attendance.knowledge.provider;

import cn.lianzhi.attendance.knowledge.retrieval.VectorMath;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Real HTTP adapter for a server-configured Ollama /api/embed endpoint; not automatically wired.
 * No redirects/retries/model downloads, no synthetic vectors or provider response in diagnostics.
 * AVAILABLE means configured, not verified network/model health. */
public final class OllamaEmbeddingProvider implements EmbeddingProvider {
    private static final int MAX_RESPONSE = 8 * 1024 * 1024;
    private final URI endpoint;
    private final String model;
    private final Duration timeout;
    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper();

    public OllamaEmbeddingProvider(String baseUrl, String model) { this(baseUrl, model, Duration.ofSeconds(20)); }
    public OllamaEmbeddingProvider(String baseUrl, String model, Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.toMillis() < 1 || timeout.compareTo(Duration.ofSeconds(60)) > 0)
            throw new IllegalArgumentException("向量请求超时需为0～60秒之间");
        if (baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()) {
            endpoint = null; this.model = "";
        } else {
            URI base;
            try { base = URI.create(baseUrl.strip()); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("向量服务地址无效"); }
            if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getHost() == null
                    || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                    || !(base.getPath().isEmpty() || base.getPath().equals("/")))
                throw new IllegalArgumentException("向量服务地址须为不含凭据、路径和查询的HTTP(S)服务根地址");
            if (model.length() > 200 || model.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("向量模型名称无效");
            endpoint = base.resolve("/api/embed"); this.model = model.strip();
        }
        client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public ProviderAvailability availability() {
        return endpoint == null ? ProviderAvailability.NOT_CONFIGURED : ProviderAvailability.AVAILABLE;
    }
    @Override public ProviderResult<List<Embedding>> embed(List<String> texts) {
        if (endpoint == null) return new ProviderResult<>(ProviderResult.State.UNAVAILABLE, "向量服务尚未配置", null);
        if (texts == null || texts.isEmpty() || texts.size() > 32) return failure("向量输入数量无效");
        int total = 0;
        for (String text : texts) {
            if (text == null || text.isBlank() || text.length() > 16_000) return failure("向量输入内容无效或超限");
            total += text.length();
        }
        if (total > 64_000) return failure("向量批次内容超限");
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            byte[] body = json.writeValueAsBytes(Map.of("model", model, "input", List.copyOf(texts), "truncate", false));
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            // The future completes only after bounded body consumption. The outer timeout also
            // bounds a server that sends headers promptly but never finishes its response body.
            pending = client.sendAsync(request, ignored -> new LimitedBody(MAX_RESPONSE));
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) return failure("向量服务返回失败状态");
            JsonNode root = json.readTree(response.body());
            if (root == null || !root.path("model").isTextual() || !model.equals(root.path("model").asText()))
                return failure("向量服务模型不匹配");
            JsonNode values = root.path("embeddings");
            if (!values.isArray() || values.size() != texts.size()) return failure("向量输出数量不匹配");
            List<Embedding> result = new ArrayList<>(); int dimension = 0;
            for (JsonNode row : values) {
                if (!row.isArray() || row.isEmpty() || row.size() > 4096 || (dimension != 0 && row.size() != dimension))
                    return failure("向量输出维度无效");
                dimension = row.size(); List<Double> numbers = new ArrayList<>();
                for (JsonNode number : row) {
                    if (!number.isNumber() || !Double.isFinite(number.doubleValue())) return failure("向量输出数值无效");
                    numbers.add(number.doubleValue());
                }
                var vector = new Embedding(numbers); VectorMath.unit(vector); result.add(vector);
            }
            return new ProviderResult<>(ProviderResult.State.SUCCESS, "向量生成完成", List.copyOf(result));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return failure("向量请求被中断");
        } catch (IOException | ExecutionException | TimeoutException | RuntimeException e) {
            return failure("向量服务不可用、超时或返回无效数据");
        } finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
    }
    private static ProviderResult<List<Embedding>> failure(String message) {
        return new ProviderResult<>(ProviderResult.State.FAILURE, message, null);
    }
    /** Enforces a limit while receiving, including chunked bodies, before allocating the full body. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription s) { subscription = s; s.request(1); }
        @Override public void onNext(List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IOException("response limit")); return;
                }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
