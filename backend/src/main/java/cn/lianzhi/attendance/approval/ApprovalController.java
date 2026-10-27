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
    public ApiResponse<?> create() { service.requireStorage(); return ApiResponse.ok(null); }
    @GetMapping("/me")
    public ApiResponse<?> mine(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { PageResult.validate(page,size); service.requireStorage(); return ApiResponse.ok(null); }
    @GetMapping("/inbox")
    @PreAuthorize("hasAnyRole('ADMIN','HR')")
    public ApiResponse<?> inbox(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { PageResult.validate(page,size); service.requireStorage(); return ApiResponse.ok(null); }
}
