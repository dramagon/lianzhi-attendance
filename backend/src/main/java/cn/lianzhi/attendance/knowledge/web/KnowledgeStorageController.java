package cn.lianzhi.attendance.knowledge.web;

import cn.lianzhi.attendance.common.*;
import cn.lianzhi.attendance.knowledge.storage.*;
import cn.lianzhi.attendance.knowledge.provider.*;
import cn.lianzhi.attendance.knowledge.retrieval.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController @RequestMapping("/api/knowledge")
@ConditionalOnProperty(name="LIANZHI_KNOWLEDGE_ENABLED",havingValue="true",matchIfMissing=true)
public class KnowledgeStorageController {
    private final KnowledgeDocuments documents;private final EmbeddingProvider embedding;private final OcrProvider ocr;private final TranscriptionProvider transcription;private final KnowledgeIdentity identity;
    public KnowledgeStorageController(KnowledgeDocuments documents,EmbeddingProvider embedding,OcrProvider ocr,TranscriptionProvider transcription,KnowledgeIdentity identity){this.documents=documents;this.embedding=embedding;this.ocr=ocr;this.transcription=transcription;this.identity=identity;}
    @GetMapping("/status") public ApiResponse<?> status(Authentication actor){identity.current(actor);boolean enabled=documents.available();var blockers=new ArrayList<String>();if(!enabled)blockers.add("知识库持久化未启用");if(embedding.availability()!=ProviderAvailability.AVAILABLE)blockers.add("向量服务未配置，文字检索可用");if(ocr.availability()!=ProviderAvailability.AVAILABLE)blockers.add("图像OCR未配置");if(transcription.availability()!=ProviderAvailability.AVAILABLE)blockers.add("音视频转写未配置");return ApiResponse.ok(Map.of("available",enabled,"phase",enabled?"CONNECTED":"DEPENDENCIES_PENDING","uploadAvailable",enabled,"textSearchAvailable",enabled,"vectorSearchAvailable",enabled&&embedding.availability()==ProviderAvailability.AVAILABLE,"blockers",blockers));}
    @GetMapping("/documents") public ApiResponse<?> list(Authentication actor,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.ok(documents.list(actor,page,size));}
    @PostMapping(value="/documents",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<?> upload(Authentication actor,@RequestParam long companyId,@RequestParam(defaultValue="PRIVATE") DocumentAccess.Visibility visibility,@RequestPart MultipartFile file)throws java.io.IOException{return ApiResponse.ok(documents.upload(actor,companyId,visibility,file.getOriginalFilename(),file.getBytes()));}
    @GetMapping("/documents/{id}") public ApiResponse<?> get(Authentication actor,@PathVariable UUID id){return ApiResponse.ok(documents.get(actor,id));}
    @PutMapping(value="/documents/{id}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<?> replace(Authentication actor,@PathVariable UUID id,@RequestParam long expectedVersion,@RequestParam DocumentAccess.Visibility visibility,@RequestPart MultipartFile file)throws java.io.IOException{return ApiResponse.ok(documents.replace(actor,id,expectedVersion,visibility,file.getOriginalFilename(),file.getBytes()));}
    @DeleteMapping("/documents/{id}") public ApiResponse<?> delete(Authentication actor,@PathVariable UUID id,@RequestParam long expectedVersion){documents.delete(actor,id,expectedVersion);return ApiResponse.ok(null);}
    @PostMapping("/documents/{id}/retry") public ApiResponse<?> retry(Authentication actor,@PathVariable UUID id,@RequestParam long expectedVersion){return ApiResponse.ok(documents.retry(actor,id,expectedVersion));}
    @GetMapping("/documents/{id}/revisions") public ApiResponse<?> history(Authentication actor,@PathVariable UUID id){return ApiResponse.ok(documents.revisions(actor,id));}
    @GetMapping("/documents/{id}/sources/{sequence}") public ApiResponse<?> cite(Authentication actor,@PathVariable UUID id,@PathVariable int sequence,@RequestParam long version){return ApiResponse.ok(documents.cite(actor,id,version,sequence));}
    @GetMapping("/documents/{id}/file") public ResponseEntity<byte[]> original(Authentication actor,@PathVariable UUID id){var file=documents.original(actor,id);return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString()).header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(file.bytes());}
    @GetMapping("/search") public ApiResponse<?> search(Authentication actor,@RequestParam String query,@RequestParam(defaultValue="TEXT") KnowledgeWorkspace.Mode mode,@RequestParam(defaultValue="10") int limit,@RequestParam(defaultValue="0") double minimumScore){return ApiResponse.ok(documents.search(actor,query,mode,limit,minimumScore));}
}
