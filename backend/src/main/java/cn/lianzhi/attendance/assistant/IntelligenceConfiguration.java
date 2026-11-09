package cn.lianzhi.attendance.assistant;

import cn.lianzhi.attendance.knowledge.provider.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.alibaba.nacos.api.naming.NamingService;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class IntelligenceConfiguration {
    @Bean OcrProvider ocrProvider(@Value("${LIANZHI_OCR_URL:}") String url,@Value("${LIANZHI_OCR_TOKEN:}") String token){
        var client=new MediaTextClient(url,token,false);return new OcrProvider(){public ProviderAvailability availability(){return client.availability();}public ProviderResult<java.util.List<cn.lianzhi.attendance.knowledge.text.TextSection>> recognize(String name,byte[] bytes){return client.extract(name,bytes);}};
    }
    @Bean TranscriptionProvider transcriptionProvider(@Value("${LIANZHI_TRANSCRIPTION_URL:}") String url,@Value("${LIANZHI_TRANSCRIPTION_TOKEN:}") String token){
        var client=new MediaTextClient(url,token,true);return new TranscriptionProvider(){public ProviderAvailability availability(){return client.availability();}public ProviderResult<java.util.List<cn.lianzhi.attendance.knowledge.text.TextSection>> transcribe(String name,byte[] bytes){return client.extract(name,bytes);}};
    }
    @Bean CitedAnswerService citedAnswerService(cn.lianzhi.attendance.knowledge.storage.KnowledgeDocuments documents,CitedModel model){return new CitedAnswerService(documents,model);}
    @Bean CitedModel citedModel(@Value("${LIANZHI_AI_BASE_URL:}") String base,@Value("${LIANZHI_AI_MODEL:}") String model,@Value("${LIANZHI_AI_TIMEOUT_SECONDS:45}") int timeout){
        return new OllamaCitedModel(base,model,java.time.Duration.ofSeconds(timeout));
    }
    @Bean
    @ConditionalOnProperty(name = "LIANZHI_EMBEDDING_DISCOVERY", havingValue = "false", matchIfMissing = true)
    EmbeddingProvider embeddingProvider(@Value("${LIANZHI_EMBEDDING_BASE_URL:}") String base,@Value("${LIANZHI_EMBEDDING_MODEL:}") String model){
        return new OllamaEmbeddingProvider(base,model);
    }

    @Bean(destroyMethod = "shutDown")
    @ConditionalOnProperty(name = "LIANZHI_EMBEDDING_DISCOVERY", havingValue = "true")
    NamingService embeddingRegistry(@Value("${LIANZHI_NACOS_ADDRESS:}") String address,
                                    @Value("${LIANZHI_NACOS_NAMESPACE:}") String namespace,
                                    @Value("${LIANZHI_NACOS_USERNAME:}") String username,
                                    @Value("${LIANZHI_NACOS_PASSWORD:}") String password) throws Exception {
        return EmbeddingRegistry.connect(address, namespace, username, password);
    }

    @Bean
    @ConditionalOnProperty(name = "LIANZHI_EMBEDDING_DISCOVERY", havingValue = "true")
    EmbeddingProvider discoveredEmbeddingProvider(NamingService registry, ObjectMapper json,
            @Value("${LIANZHI_NACOS_GROUP:DEFAULT_GROUP}") String group,
            @Value("${LIANZHI_EMBEDDING_MODEL:}") String model,
            @Value("${LIANZHI_EMBEDDING_REVISION:1}") String revision,
            @Value("${LIANZHI_EMBEDDING_WORKER_TOKEN:}") String token) {
        return new DiscoveredEmbeddingProvider(registry, group, model, revision, token, json);
    }
}
