package cn.lianzhi.attendance.assistant;

import cn.lianzhi.attendance.common.*;
import cn.lianzhi.attendance.knowledge.provider.*;
import cn.lianzhi.attendance.attendance.AttendanceDays;
import cn.lianzhi.attendance.approval.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
    private final AssistantQueries queries;
    private final CitedModel model;
    private final EmbeddingProvider embedding;
    private final AssistantActions actions;
    private final AssistantStatistics statistics;
    private final ApprovalService approvals;
    private final cn.lianzhi.attendance.knowledge.storage.KnowledgeDocuments documents;
    private final CitedAnswerService answers;private final OcrProvider ocr;private final TranscriptionProvider transcription;private final ApprovalDraftSuggestions suggestions;
    public AssistantController(AssistantQueries queries,CitedModel model,EmbeddingProvider embedding,AssistantActions actions,AssistantStatistics statistics,ApprovalService approvals,cn.lianzhi.attendance.knowledge.storage.KnowledgeDocuments documents,CitedAnswerService answers,OcrProvider ocr,TranscriptionProvider transcription,ApprovalDraftSuggestions suggestions){this.queries=queries;this.model=model;this.embedding=embedding;this.actions=actions;this.statistics=statistics;this.approvals=approvals;this.documents=documents;this.answers=answers;this.ocr=ocr;this.transcription=transcription;this.suggestions=suggestions;}
    public record Capability(boolean knowledgeAvailable,boolean businessQueriesAvailable,boolean writeAvailable,
                             String modelConfiguration,String embeddingConfiguration,String ocrConfiguration,String transcriptionConfiguration,List<String> blockers){}
    @GetMapping("/status") public ApiResponse<Capability> status(){
        return ApiResponse.ok(new Capability(documents.available(),true,approvals.status().available(),configuration(model.availability()),configuration(embedding.availability()),configuration(ocr.availability()),configuration(transcription.availability()),
                List.of("模型配置状态不代表实际联通，问答需要有权访问的文档和有效来源")));
    }
    private String configuration(ProviderAvailability value){return value==ProviderAvailability.NOT_CONFIGURED?"NOT_CONFIGURED":"CONFIGURED_NOT_VERIFIED";}
    public record Question(String question){}
    public record DraftInstruction(String instruction){}
    @PostMapping("/approval-suggestions") public ApiResponse<?> suggest(Authentication actor,@RequestBody DraftInstruction input){return ApiResponse.ok(suggestions.suggest(actor,input.instruction()));}
    @PostMapping("/ask") public ResponseEntity<?> ask(Authentication actor,@RequestBody(required=false) Question input){
        if(!documents.available()||model.availability()!=ProviderAvailability.AVAILABLE)return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.error("ASSISTANT_UNAVAILABLE","知识库或模型未配置，暂不能生成有依据的制度回答"));
        if(input==null||input.question()==null||input.question().codePointCount(0,input.question().length())>500)throw new IllegalArgumentException("问题需为1至500字");
        return ResponseEntity.ok(ApiResponse.ok(answers.ask(actor,input.question())));
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
    @GetMapping("/attendance/summary") public ApiResponse<AssistantStatistics.Summary> summary(Authentication actor,
            @RequestParam(required=false) Long companyId,@RequestParam(required=false) Long departmentId,@RequestParam LocalDate from,@RequestParam LocalDate to){
        return ApiResponse.ok(statistics.summarize(actor,companyId,departmentId,from,to));
    }
    @PostMapping("/approval-drafts") public ApiResponse<AssistantActions.Confirmation> prepare(Authentication actor,HttpSession session,@RequestBody AssistantActions.Prepare input){
        return ApiResponse.ok(actions.prepare(actor,session,input));
    }
    @PostMapping("/approval-drafts/confirm") public ApiResponse<ApprovalStore.Entry> confirm(Authentication actor,HttpSession session,@RequestBody AssistantActions.Confirm input){
        return ApiResponse.ok(actions.confirm(actor,session,input));
    }
    @PostMapping("/approval-drafts/{id}/cancel") public ApiResponse<Void> cancel(Authentication actor,HttpSession session,@PathVariable String id){
        actions.cancel(actor,session,id);return ApiResponse.ok(null);
    }
}
