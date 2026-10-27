package cn.lianzhi.attendance.organization;

import cn.lianzhi.attendance.common.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {
    private final OrganizationService service;
    public OrganizationController(OrganizationService service) { this.service = service; }
    @GetMapping("/companies")
    public ApiResponse<?> companies(Authentication actor, @RequestParam(required=false) Long companyId, @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) { return ApiResponse.ok(service.companies(actor,companyId,page,size)); }
    @PostMapping("/companies")
    public ApiResponse<?> createCompany(Authentication actor,@RequestBody OrganizationService.NameInput input) { service.company(actor,null,input); return ApiResponse.ok(null); }
    @PutMapping("/companies/{id}")
    public ApiResponse<?> updateCompany(Authentication actor,@PathVariable Long id,@RequestBody OrganizationService.NameInput input) { service.company(actor,id,input); return ApiResponse.ok(null); }
    @GetMapping("/departments")
    public ApiResponse<?> departments(Authentication actor,@RequestParam(required=false) Long companyId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return ApiResponse.ok(service.departments(actor,companyId,page,size)); }
    @PostMapping("/departments")
    public ApiResponse<?> createDepartment(Authentication actor,@RequestBody OrganizationService.DepartmentInput input) { service.department(actor,null,input); return ApiResponse.ok(null); }
    @PutMapping("/departments/{id}")
    public ApiResponse<?> updateDepartment(Authentication actor,@PathVariable Long id,@RequestBody OrganizationService.DepartmentInput input) { service.department(actor,id,input); return ApiResponse.ok(null); }
}
