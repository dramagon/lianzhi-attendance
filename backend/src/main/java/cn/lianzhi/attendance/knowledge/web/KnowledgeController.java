package cn.lianzhi.attendance.knowledge.web;

import cn.lianzhi.attendance.common.ApiResponse;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Dependency gate only. No workspace bean, upload parser, identity binding or V6 execution. */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    public record Capability(boolean available, String phase, boolean uploadAvailable,
                             boolean textSearchAvailable, boolean vectorSearchAvailable, List<String> blockers) {}
    @GetMapping("/status")
    public ApiResponse<Capability> status() {
        return ApiResponse.ok(new Capability(false, "DEPENDENCIES_PENDING", false, false, false,
                List.of("知识库持久化尚未接入", "知识库处理服务尚未开放", "向量服务尚未配置或验证")));
    }
    @RequestMapping(value = {"/documents", "/documents/{id}", "/documents/{id}/sources/{sequence}", "/search"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<ApiResponse<Void>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("KNOWLEDGE_UNAVAILABLE", "知识库尚未接入，请稍后重试"));
    }
}
