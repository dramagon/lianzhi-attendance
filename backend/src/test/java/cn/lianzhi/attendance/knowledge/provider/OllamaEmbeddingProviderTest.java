package cn.lianzhi.attendance.knowledge.provider;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** Local protocol fixture only. It does NOT validate a real Ollama installation/model. */
class OllamaEmbeddingProviderTest {
    HttpServer server;
    ExecutorService executor;
    String base;
    final AtomicReference<String> body = new AtomicReference<>();
    final AtomicReference<String> method = new AtomicReference<>();
    final AtomicInteger calls = new AtomicInteger();
    @AfterEach void stop() { if (server != null) server.stop(0); if (executor != null) executor.shutdownNow(); }
    void serve(int status, String response) throws Exception {
        start(exchange -> {
            calls.incrementAndGet(); method.set(exchange.getRequestMethod());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
    }
    void start(HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/api/embed", handler); server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    OllamaEmbeddingProvider provider() { return new OllamaEmbeddingProvider(base, "test-model", Duration.ofSeconds(2)); }
    @Test void notConfiguredReturnsUnavailableWithoutNetwork() {
        var provider = new OllamaEmbeddingProvider("", "");
        assertThat(provider.availability()).isEqualTo(ProviderAvailability.NOT_CONFIGURED);
        assertThat(provider.embed(List.of("制度")).state()).isEqualTo(ProviderResult.State.UNAVAILABLE);
    }
    @Test void realHttpProtocolSendsBatchAndDisablesSilentTruncation() throws Exception {
        serve(200, "{\"model\":\"test-model\",\"embeddings\":[[1,0],[0,2]]}");
        var result = provider().embed(List.of("合成制度甲", "合成制度乙"));
        assertThat(result.state()).isEqualTo(ProviderResult.State.SUCCESS);
        assertThat(result.value()).hasSize(2);
        assertThat(result.value().get(1).values()).containsExactly(0d, 2d);
        var sent = new ObjectMapper().readTree(body.get());
        assertThat(sent.path("truncate").booleanValue()).isFalse();
        assertThat(sent.path("model").asText()).isEqualTo("test-model");
        assertThat(sent.path("input").size()).isEqualTo(2);
        assertThat(method.get()).isEqualTo("POST");
        assertThatThrownBy(() -> result.value().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "not json", "{\"model\":\"wrong\",\"embeddings\":[[1]]}",
            "{\"model\":\"test-model\",\"embeddings\":[]}",
            "{\"model\":\"test-model\",\"embeddings\":[[0,0]]}",
            "{\"model\":\"test-model\",\"embeddings\":[[\"1\"]]}",
            "{\"model\":\"test-model\",\"embeddings\":[[1e999]]}"})
    void rejectsMalformedPartialWrongModelZeroAndNonNumericOutputs(String response) throws Exception {
        serve(200, response); var result = provider().embed(List.of("制度"));
        assertThat(result.state()).isEqualTo(ProviderResult.State.FAILURE);
        assertThat(result.value()).isNull();
    }
    @Test void rejectsInconsistentBatchDimensions() throws Exception {
        serve(200, "{\"model\":\"test-model\",\"embeddings\":[[1],[1,2]]}");
        assertThat(provider().embed(List.of("甲", "乙")).state()).isEqualTo(ProviderResult.State.FAILURE);
    }
    @Test void rejectsLargeDimension() throws Exception {
        serve(200, "{\"model\":\"test-model\",\"embeddings\":[[" + "1,".repeat(4096) + "1]]}");
        assertThat(provider().embed(List.of("甲")).state()).isEqualTo(ProviderResult.State.FAILURE);
    }
    @Test void inputBudgetsRejectBeforeSending() throws Exception {
        serve(200, "SECRET"); var p = provider();
        for (List<String> texts : List.of(List.<String>of(), Collections.nCopies(33, "x"), List.of(" "), List.of("a".repeat(16001)), Collections.nCopies(5, "a".repeat(16000))))
            assertThat(p.embed(texts).state()).isEqualTo(ProviderResult.State.FAILURE);
        assertThat(p.embed(null).state()).isEqualTo(ProviderResult.State.FAILURE);
        assertThat(calls).hasValue(0);
    }
    @Test void httpErrorDoesNotExposeProviderResponseOrRetry() throws Exception {
        serve(500, "SECRET provider diagnostic"); var result = provider().embed(List.of("制度"));
        assertThat(result.state()).isEqualTo(ProviderResult.State.FAILURE);
        assertThat(result.message()).doesNotContain("SECRET", "diagnostic");
        assertThat(calls).hasValue(1);
    }
    @Test void redirectsAreNotFollowed() throws Exception {
        start(exchange -> {
            calls.incrementAndGet(); exchange.getResponseHeaders().add("Location", base + "/other");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        assertThat(provider().embed(List.of("制度")).state()).isEqualTo(ProviderResult.State.FAILURE);
        assertThat(calls).hasValue(1);
    }
    @Test void responseBudgetAppliesToChunkedBodies() throws Exception {
        start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write(" ".repeat(8 * 1024 * 1024 + 1).getBytes(StandardCharsets.UTF_8));
            }
        });
        assertThat(provider().embed(List.of("制度")).state()).isEqualTo(ProviderResult.State.FAILURE);
    }
    @Test void timeoutIncludesHeadersAndSlowBody() throws Exception {
        start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write('{'); out.flush();
                try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        long start = System.nanoTime();
        var result = new OllamaEmbeddingProvider(base, "test-model", Duration.ofMillis(150)).embed(List.of("制度"));
        assertThat(result.state()).isEqualTo(ProviderResult.State.FAILURE);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    }
    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp", "http://user:secret@localhost", "https://localhost/a", "https://localhost?q=x", "https://localhost#fragment"})
    void rejectsUnsafeConfiguredBaseUrls(String url) {
        assertThatThrownBy(() -> new OllamaEmbeddingProvider(url, "test-model")).isInstanceOf(IllegalArgumentException.class);
    }
}
