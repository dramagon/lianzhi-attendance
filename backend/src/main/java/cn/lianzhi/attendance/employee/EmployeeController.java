package cn.lianzhi.attendance.employee;

import cn.lianzhi.attendance.common.ApiResponse;
import cn.lianzhi.attendance.organization.DataScope;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {
    private final EmployeeService service;
    private final DataScope scope;
    public EmployeeController(EmployeeService service, DataScope scope) { this.service=service; this.scope=scope; }
    @GetMapping
    public ApiResponse<?> list(Authentication actor,@RequestParam(required=false) Long companyId,@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return ApiResponse.ok(service.list(actor,companyId,query,page,size)); }
    @GetMapping("/me")
    public ApiResponse<?> me(Authentication actor) { return ApiResponse.ok(service.get(actor,scope.actor(actor).id())); }
    @GetMapping("/{id}")
    public ApiResponse<?> get(Authentication actor,@PathVariable Long id) { return ApiResponse.ok(service.get(actor,id)); }
    @GetMapping("/{id}/workplaces")
    public ApiResponse<?> workplaces(Authentication actor,@PathVariable Long id) { return ApiResponse.ok(service.workplaces(actor,id)); }
    @PostMapping
    public ApiResponse<?> create(Authentication actor,@RequestBody EmployeeService.EmployeeInput input) { return ApiResponse.ok(service.save(actor,null,input)); }
    @PutMapping("/{id}")
    public ApiResponse<?> update(Authentication actor,@PathVariable Long id,@RequestBody EmployeeService.EmployeeInput input) { return ApiResponse.ok(service.save(actor,id,input)); }
}
