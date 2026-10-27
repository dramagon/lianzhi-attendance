package cn.lianzhi.attendance.web;

import cn.lianzhi.attendance.common.ApiResponse;
import cn.lianzhi.attendance.identity.*;
import cn.lianzhi.attendance.employee.EmployeeMapper;
import cn.lianzhi.attendance.organization.OrganizationMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private final AccountMapper mapper;
    private final AccountService accounts;
    private final EmployeeMapper employees;
    private final OrganizationMapper organizations;
    public DashboardController(AccountMapper mapper, AccountService accounts, EmployeeMapper employees, OrganizationMapper organizations) { this.mapper = mapper; this.accounts = accounts; this.employees=employees; this.organizations=organizations; }

    @GetMapping("/management/overview")
    @PreAuthorize("hasAnyRole('ADMIN','HR')")
    public ApiResponse<Map<String, Object>> management(Authentication authentication) {
        var actor=mapper.findByUsername(authentication.getName());
        if (actor.role().equals("HR")) {
            Long companyId=actor.companyId();
            return ApiResponse.ok(Map.of("totalAccounts",companyId==null?0:employees.count(companyId,null,""),
                    "enabledAccounts",companyId==null?0:employees.countEnabled(companyId),
                    "roleCount",companyId==null?0:employees.countRoles(companyId),
                    "departmentCount",companyId==null?0:organizations.countDepartments(companyId),
                    "attendanceAvailable",false,"date",LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()));
        }
        return ApiResponse.ok(Map.of("totalAccounts", mapper.count(), "enabledAccounts", mapper.countEnabled(),
                "roleCount", mapper.countRoles(), "departmentCount", mapper.countDepartments(),
                "attendanceAvailable", false, "date", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()));
    }

    @GetMapping("/employee/overview")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public ApiResponse<Map<String, Object>> employee(Authentication authentication) {
        return ApiResponse.ok(Map.of("user", accounts.current(authentication.getName()),
                "attendanceAvailable", false, "date", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()));
    }

    @GetMapping("/admin/accounts")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<UserView>> users() {
        return ApiResponse.ok(mapper.findAll().stream().map(accounts::view).toList());
    }

    @GetMapping("/profile")
    public ApiResponse<UserView> profile(Authentication authentication) {
        return ApiResponse.ok(accounts.current(authentication.getName()));
    }
}
