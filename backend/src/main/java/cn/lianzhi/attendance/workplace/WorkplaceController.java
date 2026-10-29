package cn.lianzhi.attendance.workplace;

import cn.lianzhi.attendance.common.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workplaces")
public class WorkplaceController {
    private final WorkplaceService service;
    public WorkplaceController(WorkplaceService service) { this.service=service; }
    @GetMapping
    public ApiResponse<?> list(Authentication actor,@RequestParam(required=false) Long companyId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return ApiResponse.ok(service.list(actor,companyId,page,size)); }
    @PostMapping
    public ApiResponse<?> create(Authentication actor,@RequestBody WorkplaceService.WorkplaceInput input) { service.save(actor,null,input); return ApiResponse.ok(null); }
    @PutMapping("/{id}")
    public ApiResponse<?> update(Authentication actor,@PathVariable Long id,@RequestBody WorkplaceService.WorkplaceInput input) { service.save(actor,id,input); return ApiResponse.ok(null); }
}
