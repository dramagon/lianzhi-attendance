package cn.lianzhi.attendance.knowledge.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "LIANZHI_C_NACOS_IT", matches = "true")
class NacosEmbeddingIT {
    private static final List<String> INPUT = List.of("年假需要提前申请", "打卡时间以服务器为准");

    @Test
    void discoversRealWorkerAndRecoversAfterProcessRestart() throws Exception {
        String model = System.getenv("LIANZHI_EMBEDDING_MODEL");
        String group = "VERIFY_" + UUID.randomUUID().toString().replace("-", "");
        String token = UUID.randomUUID().toString();
        var registry = EmbeddingRegistry.connect(System.getenv("LIANZHI_NACOS_ADDRESS"), "",
                System.getenv().getOrDefault("LIANZHI_NACOS_USERNAME", ""),
                System.getenv().getOrDefault("LIANZHI_NACOS_PASSWORD", ""));
        var json = new ObjectMapper();
        var caller = new DiscoveredEmbeddingProvider(registry, group, model, "1", token, json);
        Process worker = null;
        try {
            assertEquals(ProviderResult.State.FAILURE, caller.embed(INPUT).state());
            worker = start(group, token);
            assertRealVectors(awaitVectors(caller, worker));
            var denied = new DiscoveredEmbeddingProvider(registry, group, model, "1",
                    UUID.randomUUID().toString(), json);
            assertEquals(ProviderResult.State.FAILURE, denied.embed(INPUT).state());
            var wrongRevision = new DiscoveredEmbeddingProvider(registry, group, model, "2", token, json);
            assertEquals(ProviderResult.State.FAILURE, wrongRevision.embed(INPUT).state());

            stop(worker);
            assertEquals(ProviderResult.State.FAILURE, caller.embed(INPUT).state());
            worker = start(group, token);
            assertRealVectors(awaitVectors(caller, worker));
        } finally {
            if (worker != null) stop(worker);
            registry.shutDown();
        }
    }

    private Process start(String group, String token) throws Exception {
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path"), EmbeddingWorker.class.getName());
        process.environment().put("LIANZHI_NACOS_GROUP", group);
        process.environment().put("LIANZHI_EMBEDDING_WORKER_TOKEN", token);
        process.environment().put("LIANZHI_EMBEDDING_WORKER_PORT", "0");
        process.environment().put("LIANZHI_EMBEDDING_REVISION", "1");
        return process.redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(Path.of("target", "c-nacos-worker.log").toFile()))
                .start();
    }

    private ProviderResult<List<Embedding>> awaitVectors(EmbeddingProvider caller, Process worker) throws Exception {
        var deadline = Instant.now().plus(Duration.ofSeconds(60));
        ProviderResult<List<Embedding>> result;
        do {
            assertTrue(worker.isAlive(), "Check target/c-nacos-worker.log");
            result = caller.embed(INPUT);
            if (result.state() == ProviderResult.State.SUCCESS) return result;
            Thread.sleep(500);
        } while (Instant.now().isBefore(deadline));
        return result;
    }

    private void assertRealVectors(ProviderResult<List<Embedding>> result) {
        assertEquals(ProviderResult.State.SUCCESS, result.state(), result.message());
        assertEquals(2, result.value().size());
        assertTrue(result.value().get(0).values().size() >= 128);
        assertFalse(result.value().get(0).equals(result.value().get(1)));
    }

    private void stop(Process worker) throws Exception {
        worker.destroy();
        if (!worker.waitFor(5, TimeUnit.SECONDS)) {
            worker.destroyForcibly();
            assertTrue(worker.waitFor(5, TimeUnit.SECONDS));
        }
    }
}
