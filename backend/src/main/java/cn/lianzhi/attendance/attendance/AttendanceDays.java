package cn.lianzhi.attendance.attendance;

import cn.lianzhi.attendance.approval.*;
import cn.lianzhi.attendance.employee.EmployeeService;
import cn.lianzhi.attendance.organization.DataScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** Reproducible daily projection: original events and immutable approved adjustments remain separate. */
@Service
public class AttendanceDays implements ApprovalAttendancePort {
    private final AttendanceSchedules schedules; private final AttendanceStore store;
    private final JdbcTemplate jdbc; private final ObjectMapper json; private final DataScope scope; private final EmployeeService employees;
    public AttendanceDays(AttendanceSchedules schedules,AttendanceStore store,JdbcTemplate jdbc,ObjectMapper json,DataScope scope,EmployeeService employees){this.schedules=schedules;this.store=store;this.jdbc=jdbc;this.json=json;this.scope=scope;this.employees=employees;}
    public record Day(long userId,LocalDate businessDate,long workplaceId,LocalDateTime checkIn,LocalDateTime checkOut,
        AttendanceRules.Result attendance,long leaveMinutes,long overtimeMinutes,List<Long> approvalIds){}
    private record Change(long id,ApprovalRules.Draft draft){}
    private List<Change> changes(long user){return jdbc.query("SELECT approval_id,draft_json FROM attendance_adjustment WHERE user_id=? ORDER BY approval_id",(rs,n)->{try{return new Change(rs.getLong(1),json.readValue(rs.getString(2),ApprovalRules.Draft.class));}catch(Exception e){throw new IllegalStateException("审批调整读取失败",e);}},user);}
    public LocalDateTime adjustedPunch(long user,LocalDate date,String type) {
        var s=schedules.get(user,date);var start=date.atTime(s.rule().start());var end=AttendanceRules.finish(date,s.rule().start(),s.rule().end());
        return changes(user).stream().map(Change::draft).filter(d->d.type()==ApprovalRules.Type.MISSING_PUNCH && d.punchType().name().equals(type) && d.workplaceId()==s.workplaceId() && !d.startAt().isBefore(start.minusHours(4)) && !d.startAt().isAfter(end.plusHours(4))).map(ApprovalRules.Draft::startAt).findFirst().orElse(null);
    }
    public boolean overtimeAllowed(long user,LocalDateTime now) {
        return changes(user).stream().map(Change::draft).anyMatch(d->d.type()==ApprovalRules.Type.OVERTIME && !now.isBefore(d.startAt()) && !now.isAfter(d.endAt()));
    }
    public boolean available(){return store.ready();}
    public void validate(Authentication actor,Adjustment a){
        if(a==null || a.approvalId()<1 || a.approvedAt()==null)throw new IllegalArgumentException("审批调整缺少标识与服务端时间");
        scope.manager(actor);var manager=scope.actor(actor);
        if(manager.id()!=a.approverId() || manager.id()==a.applicantId())throw new org.springframework.security.access.AccessDeniedException("审批人身份不符或不能自审");
        var user=employees.get(actor,a.applicantId());
        if(!user.enabled() || !Objects.equals(user.companyId(),a.companyId()))throw new org.springframework.security.access.AccessDeniedException("申请人与公司不匹配");
        var draft=ApprovalRules.validate(a.draft());
        var shifts=schedules.list(a.applicantId(),draft.startAt().toLocalDate().minusDays(1),draft.endAt()==null?draft.startAt().toLocalDate():draft.endAt().toLocalDate());
        if(draft.type()==ApprovalRules.Type.MISSING_PUNCH){
            var matches=shifts.stream().filter(s->s.workplaceId()==draft.workplaceId() && !draft.startAt().isBefore(s.businessDate().atTime(s.rule().start()).minusHours(4)) && !draft.startAt().isAfter(AttendanceRules.finish(s.businessDate(),s.rule().start(),s.rule().end()).plusHours(4))).toList();
            if(matches.size()!=1)throw new IllegalArgumentException("补卡须对应唯一正式班次与场所");
            var s=matches.get(0);var events=store.day(a.applicantId(),s.businessDate());
            if(events.stream().anyMatch(e->e.type().equals(draft.punchType().name())))throw new IllegalArgumentException("原始记录已有该方向打卡，不可重复补卡");
        }else if(shifts.isEmpty())throw new IllegalArgumentException("申请范围尚无正式排班");
        if(draft.type()==ApprovalRules.Type.OVERTIME){for(var s:shifts){if(!s.holiday() && s.rule().workdays().contains(s.businessDate().getDayOfWeek()) && draft.startAt().isBefore(AttendanceRules.finish(s.businessDate(),s.rule().start(),s.rule().end())) && draft.endAt().isAfter(s.businessDate().atTime(s.rule().start())))throw new IllegalArgumentException("加班时段不能与正常班次重叠");}}
        if(draft.type()==ApprovalRules.Type.OVERTIME && shifts.stream().noneMatch(s->!draft.startAt().isBefore(s.businessDate().atTime(s.rule().start()).minusHours(4)) && !draft.endAt().isAfter(AttendanceRules.finish(s.businessDate(),s.rule().start(),s.rule().end()).plusHours(4))))throw new IllegalArgumentException("加班须对应一个正式班次，且在班次前后4小时窗口内");
        for(var old:changes(a.applicantId())){
            if(old.id==a.approvalId())continue;
            var d=old.draft;
            if(d.type()==ApprovalRules.Type.MISSING_PUNCH && draft.type()==d.type()){
                if(d.punchType()==draft.punchType() && d.workplaceId().equals(draft.workplaceId()) && shifts.stream().anyMatch(s->!d.startAt().isBefore(s.businessDate().atTime(s.rule().start()).minusHours(4)) && !d.startAt().isAfter(AttendanceRules.finish(s.businessDate(),s.rule().start(),s.rule().end()).plusHours(4))))throw new IllegalArgumentException("同一班次方向已存在补卡调整");
            }else if(d.endAt()!=null && draft.endAt()!=null && d.startAt().isBefore(draft.endAt()) && draft.startAt().isBefore(d.endAt()))throw new IllegalArgumentException("审批调整时段存在重叠");
        }
    }
    @Transactional
    public void apply(Authentication actor,Adjustment a){
        // Locks the same account as punch/scheduling so adjustment validation and append are atomic.
        if(a==null)throw new IllegalArgumentException("缺少审批调整");
        scope.manager(actor);employees.get(actor,a.applicantId());
        store.lockUser(a.applicantId());
        var existing=jdbc.queryForList("SELECT * FROM attendance_adjustment WHERE approval_id=?",a.approvalId());
        if(!existing.isEmpty()){
            var row=existing.get(0);scope.manager(actor);var user=employees.get(actor,a.applicantId());
            if(scope.actor(actor).id()!=a.approverId() || !Objects.equals(user.companyId(),a.companyId()) || a.approverId()==a.applicantId() || ((Number)row.get("user_id")).longValue()!=a.applicantId() || ((Number)row.get("company_id")).longValue()!=a.companyId() || ((Number)row.get("approver_id")).longValue()!=a.approverId() || !row.get("draft_json").equals(schedules.encode(ApprovalRules.validate(a.draft()))))throw new IllegalArgumentException("审批编号已用于不同调整");
            return;
        }
        validate(actor,a);
        jdbc.update("INSERT INTO attendance_adjustment(approval_id,user_id,company_id,draft_json,approver_id,approved_at) VALUES(?,?,?,?,?,?)",a.approvalId(),a.applicantId(),a.companyId(),schedules.encode(ApprovalRules.validate(a.draft())),a.approverId(),java.sql.Timestamp.valueOf(a.approvedAt()));
        // Reject corrections producing an invalid event order; transaction rolls back the adjustment.
        for(var s:schedules.list(a.applicantId(),a.draft().startAt().toLocalDate().minusDays(1),a.draft().endAt()==null?a.draft().startAt().toLocalDate():a.draft().endAt().toLocalDate()))project(s,changes(a.applicantId()));
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Day> list(Authentication actor,long user,LocalDate from,LocalDate to){
        employees.get(actor,user);
        if(from==null || to==null || to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from,to)>93)throw new IllegalArgumentException("查询最多94天，结束不得早于开始");
        var changes=changes(user);return schedules.list(user,from,to).stream().map(s->project(s,changes)).toList();
    }
    private Day project(AttendanceSchedules.Schedule s,List<Change> changes){
        LocalDateTime in=null,out=null;var events=store.day(s.userId(),s.businessDate());
        for(var e:events){var t=LocalDateTime.ofInstant(e.occurredAt(),AttendanceRules.ZONE);if(e.type().equals("IN"))in=t;else out=t;}
        var start=s.businessDate().atTime(s.rule().start());var end=AttendanceRules.finish(s.businessDate(),s.rule().start(),s.rule().end());
        List<Long> ids=new ArrayList<>();long leave=0,overtime=0;
        for(var c:changes){var d=c.draft;
            if(d.type()==ApprovalRules.Type.MISSING_PUNCH && d.workplaceId()==s.workplaceId() && !d.startAt().isBefore(start.minusHours(4)) && !d.startAt().isAfter(end.plusHours(4))){if(d.punchType()==ApprovalRules.PunchType.IN)in=d.startAt();else out=d.startAt();ids.add(c.id);}
        }
        var base=AttendanceRules.calculate(new AttendanceRules.Input(s.businessDate(),s.rule(),in,out,s.holiday(),!LocalDateTime.now(AttendanceRules.ZONE).isBefore(end)));
        long actualLeave=0, lateExempt=0, earlyExempt=0;int late=base.lateMinutes(),early=base.earlyMinutes();
        for(var c:changes){var d=c.draft;if(d.endAt()==null)continue;
            if(d.type()==ApprovalRules.Type.LEAVE){
                long credited=s.holiday() || !s.rule().workdays().contains(s.businessDate().getDayOfWeek())?0:overlapWork(s,d.startAt(),d.endAt());
                if(credited>0){leave+=credited;ids.add(c.id);if(in!=null && out!=null)actualLeave+=overlapWork(s,max(in,d.startAt()),min(out,d.endAt()));
                    if(in!=null)lateExempt+=Math.max(0,Duration.between(max(start,d.startAt()),min(in,d.endAt())).getSeconds());
                    if(out!=null)earlyExempt+=Math.max(0,Duration.between(max(out,d.startAt()),min(end,d.endAt())).getSeconds());}
            }else if(in!=null && out!=null && !d.startAt().isBefore(start.minusHours(4)) && !d.endAt().isAfter(end.plusHours(4))){long minutes=overlap(in,out,d.startAt(),d.endAt());if(minutes>0){overtime+=minutes;ids.add(c.id);}}
        }
        if(!s.holiday() && s.rule().workdays().contains(s.businessDate().getDayOfWeek()) && in!=null && out!=null){long lateSeconds=Math.max(0,Duration.between(start,in).getSeconds()-lateExempt);late=lateSeconds>s.rule().graceMinutes()*60L?(int)((lateSeconds+59)/60):0;early=(int)((Math.max(0,Duration.between(out,end).getSeconds()-earlyExempt)+59)/60);}
        String status=base.status();if(leave>0 && leave>=overlapWork(s,start,end))status="ON_LEAVE";
        else if(!s.holiday() && s.rule().workdays().contains(s.businessDate().getDayOfWeek()) && in!=null && out!=null)status=late>0&&early>0?"LATE_AND_EARLY":late>0?"LATE":early>0?"EARLY":"NORMAL";
        var adjusted=new AttendanceRules.Result(status,late,early,Math.max(0,base.workMinutes()-actualLeave),base.breakMinutes(),base.overnight(),base.notes());
        return new Day(s.userId(),s.businessDate(),s.workplaceId(),in,out,adjusted,leave,overtime,ids.stream().distinct().toList());
    }
    private long overlapWork(AttendanceSchedules.Schedule s,LocalDateTime a,LocalDateTime b){
        var start=s.businessDate().atTime(s.rule().start());var end=AttendanceRules.finish(s.businessDate(),s.rule().start(),s.rule().end());
        long n=overlap(a,b,start,end);if(s.rule().breakStart()!=null){var bs=s.businessDate().atTime(s.rule().breakStart());if(bs.isBefore(start))bs=bs.plusDays(1);var be=s.businessDate().atTime(s.rule().breakEnd());if(be.isBefore(start))be=be.plusDays(1);n-=overlap(max(a,start),min(b,end),bs,be);}return Math.max(0,n);
    }
    private static long overlap(LocalDateTime a,LocalDateTime b,LocalDateTime c,LocalDateTime d){return Math.max(0,Duration.between(max(a,c),min(b,d)).toMinutes());}
    private static LocalDateTime max(LocalDateTime a,LocalDateTime b){return a.isAfter(b)?a:b;}
    private static LocalDateTime min(LocalDateTime a,LocalDateTime b){return a.isBefore(b)?a:b;}
}
