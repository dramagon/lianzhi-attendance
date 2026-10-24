package cn.lianzhi.attendance.web;

import cn.lianzhi.attendance.common.ApiResponse;
import cn.lianzhi.attendance.identity.*;
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
    public DashboardController(AccountMapper mapper, AccountService accounts) { this.mapper = mapper; this.accounts = accounts; }

    @GetMapping("/management/overview")
    @PreAuthorize("hasAnyRole('ADMIN','HR')")
    public ApiResponse<Map<String, Object>> management() {
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
        return ApiResponse.ok(mapper.findAll().stream().map(UserView::from).toList());
    }

    @GetMapping("/profile")
    public ApiResponse<UserView> profile(Authentication authentication) {
        return ApiResponse.ok(accounts.current(authentication.getName()));
    }
}
