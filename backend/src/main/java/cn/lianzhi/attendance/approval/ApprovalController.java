package cn.lianzhi.attendance.approval;

import cn.lianzhi.attendance.common.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {
    private final ApprovalService service;
    public ApprovalController(ApprovalService service) { this.service=service; }
    @GetMapping("/status")
    public ApiResponse<?> status() { return ApiResponse.ok(service.status()); }
    @PostMapping("/preview")
    public ApiResponse<?> preview(Authentication actor,@RequestBody ApprovalRules.Draft input) { return ApiResponse.ok(service.preview(actor,input)); }
    @PostMapping
    public ApiResponse<?> create(Authentication actor,@RequestBody ApprovalService.Submission input) { return ApiResponse.ok(service.create(actor,input)); }
    @GetMapping("/me")
    public ApiResponse<?> mine(Authentication actor,@RequestParam(defaultValue="ALL") String state,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return ApiResponse.ok(service.list(actor,false,state,page,size)); }
    @GetMapping("/inbox")
    @PreAuthorize("hasAnyRole('ADMIN','HR')")
    public ApiResponse<?> inbox(Authentication actor,@RequestParam(defaultValue="PENDING") String state,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return ApiResponse.ok(service.list(actor,true,state,page,size)); }
    @GetMapping("/{id}")
    public ApiResponse<?> detail(Authentication actor,@PathVariable long id){return ApiResponse.ok(service.detail(actor,id));}
    @PostMapping("/{id}/actions")
    public ApiResponse<?> decide(Authentication actor,@PathVariable long id,@RequestBody ApprovalService.Decision input){return ApiResponse.ok(service.decide(actor,id,input));}
}
