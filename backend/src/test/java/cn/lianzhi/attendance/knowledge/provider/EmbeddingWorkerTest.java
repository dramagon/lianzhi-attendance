package cn.lianzhi.attendance.knowledge.provider;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbeddingWorkerTest {
    @Test
    void rejectsUnauthorizedAndWrongModelBeforeCallingProvider() throws Exception {
        var calls = new AtomicInteger();
        EmbeddingProvider provider = new EmbeddingProvider() {
            public ProviderAvailability availability() { return ProviderAvailability.AVAILABLE; }
            public ProviderResult<List<Embedding>> embed(List<String> texts) {
                calls.incrementAndGet();
                return new ProviderResult<>(ProviderResult.State.SUCCESS, "ok",
                        List.of(new Embedding(List.of(1.0, 2.0))));
            }
        };
        var server = EmbeddingWorker.start(new InetSocketAddress("127.0.0.1", 0), provider,
                "test-only-embedding-token-32bytes", "expected");
        try {
            var url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + EmbeddingWorker.PATH);
            assertEquals(401, post(url, "wrong", "expected"));
            assertEquals(400, post(url, "test-only-embedding-token-32bytes", "other"));
            assertEquals(0, calls.get());
            assertEquals(200, post(url, "test-only-embedding-token-32bytes", "expected"));
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    private int post(URI url, String token, String model) throws Exception {
        var request = HttpRequest.newBuilder(url).header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"model\":\"" + model + "\",\"texts\":[\"policy\"]}")).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
