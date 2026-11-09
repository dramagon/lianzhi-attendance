package cn.lianzhi.attendance.attendance;

import cn.lianzhi.attendance.common.ApiResponse;
import cn.lianzhi.attendance.employee.EmployeeService;
import cn.lianzhi.attendance.organization.DataScope;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/attendance")
public class AttendancePlanningController {
    private final AttendanceSchedules schedules;private final AttendanceDays days;private final DataScope scope;private final EmployeeService employees;
    public AttendancePlanningController(AttendanceSchedules schedules,AttendanceDays days,DataScope scope,EmployeeService employees){this.schedules=schedules;this.days=days;this.scope=scope;this.employees=employees;}
    @PostMapping("/schedules") public ApiResponse<Void> save(Authentication actor,@RequestBody AttendanceSchedules.Schedule schedule){schedules.save(actor,schedule);return ApiResponse.ok(null);}
    @GetMapping("/me/schedules") public ApiResponse<List<AttendanceSchedules.Schedule>> mine(Authentication actor,@RequestParam LocalDate from,@RequestParam LocalDate to){return ApiResponse.ok(scheduled(actor,scope.actor(actor).id(),from,to));}
    @GetMapping("/schedules") public ApiResponse<List<AttendanceSchedules.Schedule>> management(Authentication actor,@RequestParam long userId,@RequestParam LocalDate from,@RequestParam LocalDate to){scope.manager(actor);return ApiResponse.ok(scheduled(actor,userId,from,to));}
    private List<AttendanceSchedules.Schedule> scheduled(Authentication actor,long user,LocalDate from,LocalDate to){employees.get(actor,user);if(to.isBefore(from)||java.time.temporal.ChronoUnit.DAYS.between(from,to)>93)throw new IllegalArgumentException("日期范围最多94天");return schedules.list(user,from,to);}
    @GetMapping("/me/days") public ApiResponse<List<AttendanceDays.Day>> ownDays(Authentication actor,@RequestParam LocalDate from,@RequestParam LocalDate to){return ApiResponse.ok(days.list(actor,scope.actor(actor).id(),from,to));}
    @GetMapping("/days") public ApiResponse<List<AttendanceDays.Day>> managedDays(Authentication actor,@RequestParam long userId,@RequestParam LocalDate from,@RequestParam LocalDate to){scope.manager(actor);return ApiResponse.ok(days.list(actor,userId,from,to));}
}
