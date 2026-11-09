package cn.lianzhi.attendance.knowledge.provider;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/** Separate entry point without Spring, database access or application credentials. */
public final class EmbeddingWorker {
    static final String PATH = "/internal/v1/embeddings";
    static final int MAX_RESPONSE = 8 * 1024 * 1024;
    private static final int MAX_REQUEST = 400_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    record Request(String model, List<String> texts) {}
    record Result(String model, List<Embedding> embeddings) {}

    private EmbeddingWorker() {}

    public static void main(String[] args) throws Exception {
        String token = env("LIANZHI_EMBEDDING_WORKER_TOKEN", "");
        String model = env("LIANZHI_EMBEDDING_MODEL", "");
        var provider = new OllamaEmbeddingProvider(env("LIANZHI_EMBEDDING_BASE_URL", ""), model);
        if (provider.availability() != ProviderAvailability.AVAILABLE)
            throw new IllegalArgumentException("Configure the worker's upstream embedding provider");
        String host = env("LIANZHI_EMBEDDING_ADVERTISE_HOST", "127.0.0.1");
        String group = env("LIANZHI_NACOS_GROUP", "DEFAULT_GROUP");
        EmbeddingRegistry.validateToken(token);
        var registry = EmbeddingRegistry.connect(env("LIANZHI_NACOS_ADDRESS", ""),
                env("LIANZHI_NACOS_NAMESPACE", ""), env("LIANZHI_NACOS_USERNAME", ""),
                env("LIANZHI_NACOS_PASSWORD", ""));
        try {
            var server = start(new InetSocketAddress(env("LIANZHI_EMBEDDING_BIND_HOST", "127.0.0.1"),
                    Integer.parseInt(env("LIANZHI_EMBEDDING_WORKER_PORT", "8092"))), provider, token, model);
            int port = server.getAddress().getPort();
            try {
                var instance = new Instance();
                instance.setIp(host);
                instance.setPort(port);
                instance.setEphemeral(true);
                instance.setMetadata(Map.of("model", model, "revision", env("LIANZHI_EMBEDDING_REVISION", "1")));
                registry.registerInstance(EmbeddingRegistry.SERVICE, group, instance);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    server.stop(0);
                    try { registry.deregisterInstance(EmbeddingRegistry.SERVICE, group, instance); }
                    catch (Exception ignored) { /* The ephemeral instance also expires on disconnect. */ }
                    finally {
                        try { registry.shutDown(); }
                        catch (Exception ignored) { /* Process is exiting. */ }
                    }
                }));
                System.out.println("Embedding worker registered on port " + port);
            } catch (Exception e) {
                server.stop(0);
                throw e;
            }
        } catch (Exception e) {
            registry.shutDown();
            throw e;
        }
    }

    static HttpServer start(InetSocketAddress address, EmbeddingProvider provider, String token, String model)
            throws IOException {
        EmbeddingRegistry.validateToken(token);
        var server = HttpServer.create(address, 16);
        byte[] authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        server.createContext(PATH, exchange -> handle(exchange, provider, authorization, model));
        server.start();
        return server;
    }

    private static void handle(HttpExchange exchange, EmbeddingProvider provider, byte[] authorization, String model)
            throws IOException {
        try (exchange) {
            if (!PATH.equals(exchange.getRequestURI().getPath()) || !"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String supplied = exchange.getRequestHeaders().getFirst("Authorization");
            if (supplied == null || !MessageDigest.isEqual(authorization, supplied.getBytes(StandardCharsets.UTF_8))) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            Request input;
            try {
                byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST + 1);
                if (body.length > MAX_REQUEST) {
                    exchange.sendResponseHeaders(413, -1);
                    return;
                }
                input = JSON.readValue(body, Request.class);
            } catch (IOException e) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            if (input == null || !model.equals(input.model()) || !validTexts(input.texts())) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            var result = provider.embed(input.texts());
            if (result.state() != ProviderResult.State.SUCCESS) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            byte[] output = JSON.writeValueAsBytes(new Result(model, result.value()));
            if (output.length > MAX_RESPONSE) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, output.length);
            exchange.getResponseBody().write(output);
        }
    }

    static boolean validTexts(List<String> texts) {
        return texts != null && !texts.isEmpty() && texts.size() <= 32
                && texts.stream().allMatch(text -> text != null && !text.isBlank() && text.length() <= 16_000)
                && texts.stream().mapToInt(String::length).sum() <= 64_000;
    }

    private static String env(String key, String fallback) {
        return System.getenv().getOrDefault(key, fallback);
    }
}
