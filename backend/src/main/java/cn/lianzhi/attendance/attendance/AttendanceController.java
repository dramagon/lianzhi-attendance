package cn.lianzhi.attendance.attendance;

import cn.lianzhi.attendance.common.ApiResponse;
import cn.lianzhi.attendance.identity.AccountService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/attendance")
public class AttendanceController {
    private final AttendanceService attendance;
    private final AccountService accounts;
    public AttendanceController(AttendanceService attendance, AccountService accounts) { this.attendance = attendance; this.accounts = accounts; }
    private long user(Principal principal) { return accounts.current(principal.getName()).id(); }
    @GetMapping("/status")
    public ApiResponse<AttendanceService.Capability> status(Principal p) { user(p); return ApiResponse.ok(attendance.capability()); }
    @PostMapping("/rules/preview")
    public ApiResponse<AttendanceRules.Result> preview(Principal p, @RequestBody AttendanceRules.Input input) {
        user(p); return ApiResponse.ok(AttendanceRules.calculate(input));
    }
    @GetMapping("/me/workplaces")
    public ApiResponse<List<AttendanceDirectory.Workplace>> workplaces(Principal p) { return ApiResponse.ok(attendance.workplaces(user(p))); }
    @GetMapping("/managed/workplaces")
    @PreAuthorize("hasAnyRole('ADMIN','HR')")
    public ApiResponse<List<AttendanceDirectory.Workplace>> managedWorkplaces(Principal p) { return ApiResponse.ok(attendance.managedWorkplaces(user(p))); }
    @PostMapping("/workplaces/{workplaceId}/challenge")
    @PreAuthorize("hasAnyRole('ADMIN','HR')")
    public ApiResponse<QrTokens.Issued> challenge(Principal p, @PathVariable long workplaceId) { return ApiResponse.ok(attendance.issue(user(p), workplaceId)); }
    @PostMapping("/me/punches")
    public ApiResponse<AttendanceService.EventView> punch(Principal p, @RequestBody AttendanceService.Punch request) { return ApiResponse.ok(attendance.punch(user(p), request)); }
    @GetMapping("/me/records")
    public ApiResponse<AttendanceService.Records> records(Principal p, @RequestParam LocalDate from, @RequestParam LocalDate to,
                                                        @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(attendance.records(user(p), from, to, page, size));
    }
}
