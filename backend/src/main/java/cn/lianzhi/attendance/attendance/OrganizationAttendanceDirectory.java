package cn.lianzhi.attendance.attendance;

import cn.lianzhi.attendance.organization.DataScope;
import cn.lianzhi.attendance.workplace.*;
import org.springframework.stereotype.Component;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Instant;
import java.util.List;

@Component
public class OrganizationAttendanceDirectory implements AttendanceDirectory {
    private final WorkplaceService workplaces;
    private final WorkplaceMapper mapper;
    private final DataScope scope;
    private final AttendanceSchedules schedules;
    private final AttendanceDays days;
    public OrganizationAttendanceDirectory(WorkplaceService workplaces,WorkplaceMapper mapper,DataScope scope,AttendanceSchedules schedules,AttendanceDays days) {this.workplaces=workplaces;this.mapper=mapper;this.scope=scope;this.schedules=schedules;this.days=days;}
    private org.springframework.security.core.Authentication actor(){return SecurityContextHolder.getContext().getAuthentication();}
    private Workplace convert(WorkplaceView w) {
        return new Workplace(w.id(),w.name(),new GeoFence.Point(w.latitude().doubleValue(),w.longitude().doubleValue(),w.coordinateSystem()),w.radiusMeters(),false);
    }
    public List<Workplace> workplaces(long user) {
        return workplaces.activeForEmployee(actor(),user).stream().filter(w->"WGS84".equals(w.coordinateSystem())).map(this::convert).toList();
    }
    public List<Workplace> managedWorkplaces(long user) {
        scope.manager(actor());if(scope.actor(actor()).id()!=user)throw new org.springframework.security.access.AccessDeniedException("身份不匹配");
        return workplaces.list(actor(),null,1,100).items().stream().filter(w->w.enabled() && "WGS84".equals(w.coordinateSystem())).map(this::convert).toList();
    }
    public Assignment forPunch(long user,long place,Instant now) {
        if(scope.actor(actor()).id()!=user)throw new org.springframework.security.access.AccessDeniedException("只能本人打卡");
        var w=workplaces.activeForEmployee(actor(),user).stream().filter(p->p.id()==place).findFirst().orElseThrow(()->new org.springframework.security.access.AccessDeniedException("无有效场所绑定"));
        var schedule=schedules.current(user,now);
        if(schedule.workplaceId()!=place)throw new IllegalArgumentException("二维码场所与当前正式排班不一致");
        var rule=schedule.rule();boolean rest=schedule.holiday() || !rule.workdays().contains(schedule.businessDate().getDayOfWeek());
        if(rest && days.overtimeAllowed(user,java.time.LocalDateTime.ofInstant(now,AttendanceRules.ZONE)))return new Assignment(convert(w),new AttendanceRules.Rule(rule.start(),rule.end(),rule.breakStart(),rule.breakEnd(),rule.graceMinutes(),java.util.EnumSet.allOf(java.time.DayOfWeek.class)),schedule.businessDate(),false);
        return new Assignment(convert(w),rule,schedule.businessDate(),schedule.holiday());
    }
    public java.time.LocalDateTime adjustedPunch(long user,java.time.LocalDate date,String type){return days.adjustedPunch(user,date,type);}
    public void requireManager(long user,long place) {
        if(scope.actor(actor()).id()!=user)throw new org.springframework.security.access.AccessDeniedException("管理身份不匹配");
        scope.manager(actor());var w=mapper.find(place);
        if(w==null || !w.enabled())throw new IllegalArgumentException("场所不存在或已停用");
        scope.company(actor(),w.companyId());convert(w);
    }
}
