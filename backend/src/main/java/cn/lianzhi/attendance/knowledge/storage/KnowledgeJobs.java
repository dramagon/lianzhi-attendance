package cn.lianzhi.attendance.knowledge.storage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration @EnableScheduling
@ConditionalOnProperty(name="LIANZHI_KNOWLEDGE_WORKER_ENABLED",havingValue="true",matchIfMissing=true)
public class KnowledgeJobs {
    private final KnowledgeDocuments documents;
    public KnowledgeJobs(KnowledgeDocuments documents){this.documents=documents;}
    @Scheduled(fixedDelayString="${LIANZHI_KNOWLEDGE_POLL_MS:2000}",initialDelay=3000)
    public void process(){documents.processNext();}
}
