package cn.lianzhi.attendance.assistant;

import cn.lianzhi.attendance.common.*;
import cn.lianzhi.attendance.knowledge.provider.*;
import cn.lianzhi.attendance.attendance.AttendanceDays;
import cn.lianzhi.attendance.approval.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
    private final AssistantQueries queries;
    private final CitedModel model;
    private final EmbeddingProvider embedding;
    public AssistantController(AssistantQueries queries,CitedModel model,EmbeddingProvider embedding){this.queries=queries;this.model=model;this.embedding=embedding;}
    public record Capability(boolean knowledgeAvailable,boolean businessQueriesAvailable,boolean writeAvailable,
                             String modelConfiguration,String embeddingConfiguration,String ocrConfiguration,String transcriptionConfiguration,List<String> blockers){}
    @GetMapping("/status") public ApiResponse<Capability> status(){
        return ApiResponse.ok(new Capability(false,true,false,configuration(model.availability()),configuration(embedding.availability()),"NOT_CONFIGURED","NOT_CONFIGURED",
                List.of("知识文档存储尚未接入，制度问答暂不可用","模型配置状态不代表实际联通，OCR与转写尚未配置")));
    }
    private String configuration(ProviderAvailability value){return value==ProviderAvailability.NOT_CONFIGURED?"NOT_CONFIGURED":"CONFIGURED_NOT_VERIFIED";}
    @PostMapping("/ask") public ResponseEntity<ApiResponse<Void>> ask(){
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.error("ASSISTANT_UNAVAILABLE","知识库尚未接入，暂不能生成有依据的制度回答"));
    }
    @GetMapping("/attendance") public ApiResponse<List<AttendanceDays.Day>> attendance(Authentication actor,
            @RequestParam(required=false) Long userId,@RequestParam LocalDate from,@RequestParam LocalDate to){
        return ApiResponse.ok(queries.attendance(actor,userId,from,to));
    }
    @GetMapping("/approvals") public ApiResponse<PageResult<ApprovalStore.Entry>> applications(Authentication actor,
            @RequestParam(defaultValue="ALL") String state,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){
        return ApiResponse.ok(queries.applications(actor,state,page,size));
    }
    @GetMapping("/approvals/{id}") public ApiResponse<ApprovalService.Detail> application(Authentication actor,@PathVariable long id){
        return ApiResponse.ok(queries.application(actor,id));
    }
}
