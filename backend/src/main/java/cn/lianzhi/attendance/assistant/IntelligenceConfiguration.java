package cn.lianzhi.attendance.assistant;

import cn.lianzhi.attendance.knowledge.provider.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class IntelligenceConfiguration {
    @Bean CitedModel citedModel(@Value("${LIANZHI_AI_BASE_URL:}") String base,@Value("${LIANZHI_AI_MODEL:}") String model){
        return new OllamaCitedModel(base,model);
    }
    @Bean EmbeddingProvider embeddingProvider(@Value("${LIANZHI_EMBEDDING_BASE_URL:}") String base,@Value("${LIANZHI_EMBEDDING_MODEL:}") String model){
        return new OllamaEmbeddingProvider(base,model);
    }
}
