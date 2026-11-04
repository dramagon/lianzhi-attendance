package cn.lianzhi.attendance.report;

import cn.lianzhi.attendance.common.ApiResponse;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController @RequestMapping("/api/reports") @PreAuthorize("hasAnyRole('ADMIN','HR')")
public class ReportController {
    private final AttendanceReports reports;
    public ReportController(AttendanceReports reports){this.reports=reports;}
    @GetMapping("/attendance")
    public ApiResponse<?> attendance(Authentication actor,@RequestParam LocalDate from,@RequestParam LocalDate to,
        @RequestParam(required=false) Long companyId,@RequestParam(required=false) Long departmentId,@RequestParam(required=false) Long userId,@RequestParam(required=false) Long workplaceId,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){
        return ApiResponse.ok(reports.query(actor,new AttendanceReports.Filter(from,to,companyId,departmentId,userId,workplaceId),page,size));
    }
    @GetMapping(value="/attendance/export",produces="text/csv;charset=UTF-8")
    public ResponseEntity<String> export(Authentication actor,@RequestParam LocalDate from,@RequestParam LocalDate to,
        @RequestParam(required=false) Long companyId,@RequestParam(required=false) Long departmentId,@RequestParam(required=false) Long userId,@RequestParam(required=false) Long workplaceId){
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=attendance.csv").header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff")
            .body(reports.export(actor,new AttendanceReports.Filter(from,to,companyId,departmentId,userId,workplaceId)));
    }
}
