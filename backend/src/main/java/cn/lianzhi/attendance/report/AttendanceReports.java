package cn.lianzhi.attendance.report;

import cn.lianzhi.attendance.attendance.*;
import cn.lianzhi.attendance.common.*;
import cn.lianzhi.attendance.employee.*;
import cn.lianzhi.attendance.organization.*;
import cn.lianzhi.attendance.workplace.*;
import cn.lianzhi.attendance.knowledge.retrieval.KnowledgeIdentity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.math.*;
import java.util.*;

/** Reads B projections in one snapshot. Rates count completed employee-shifts, never invent missing schedules. */
@Service
public class AttendanceReports {
    private final DataScope scope; private final EmployeeService employees; private final OrganizationMapper organizations;
    private final WorkplaceMapper places; private final AttendanceDays days; private final AttendanceSchedules schedules; private final KnowledgeIdentity identity;
    public AttendanceReports(DataScope scope,EmployeeService employees,OrganizationMapper organizations,WorkplaceMapper places,AttendanceDays days,AttendanceSchedules schedules,KnowledgeIdentity identity){
        this.scope=scope;this.employees=employees;this.organizations=organizations;this.places=places;this.days=days;this.schedules=schedules;this.identity=identity;
    }
    public record Filter(LocalDate from,LocalDate to,Long companyId,Long departmentId,Long userId,Long workplaceId){}
    public record Row(long userId,String employeeNo,String employeeName,Long companyId,Long departmentId,String departmentName,
                      LocalDate date,long workplaceId,String workplaceName,String status,boolean finalized,
                      LocalDateTime checkIn,LocalDateTime checkOut,int lateMinutes,int earlyMinutes,long workMinutes,long leaveMinutes,long overtimeMinutes,List<Long> approvalIds){}
    public record Totals(long scheduledDays,long pendingDays,long expectedDays,long attendedDays,BigDecimal attendanceRate,
                         long lateDays,long earlyDays,long absentDays,long missingPunchDays,long leaveDays,long restDays,
                         long workMinutes,long leaveMinutes,long overtimeMinutes){}
    public record Trend(LocalDate date,Totals totals){}
    public record Heat(long workplaceId,String name,BigDecimal latitude,BigDecimal longitude,String coordinateSystem,long scheduledDays,long attendedDays,long workMinutes){}
    public record Hour(int hour,long checkIns,long checkOuts){}
    public record Result(Instant generatedAt,String timeZone,String source,int employeeCount,Totals totals,List<Trend> trend,List<Heat> workplaces,PageResult<Row> records,WorkForecast.Forecast forecast,List<Hour> hours){}
    private record Snapshot(List<Row> rows,int employeeCount,List<Heat> heat){}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Result query(Authentication actor,Filter filter,int page,int size){
        PageResult.validate(page,size);Instant now=Instant.now();var snapshot=read(actor,filter,now);
        var rows=snapshot.rows();int start=(int)Math.min((long)(page-1)*size,rows.size());
        var trend=new ArrayList<Trend>();
        for(LocalDate date=filter.from();!date.isAfter(filter.to());date=date.plusDays(1)){
            LocalDate d=date;trend.add(new Trend(d,total(rows.stream().filter(r->r.date().equals(d)).toList())));
        }
        return new Result(now,"Asia/Shanghai","AttendanceDays.list",snapshot.employeeCount(),total(rows),List.copyOf(trend),snapshot.heat(),new PageResult<>(rows.subList(start,Math.min(start+size,rows.size())),rows.size(),page,size),WorkForecast.calculate(trend),hours(rows));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public String export(Authentication actor,Filter filter){
        var rows=read(actor,filter,Instant.now()).rows();
        StringBuilder csv=new StringBuilder("\uFEFF");
        line(csv,List.of("员工ID","工号","姓名","公司ID","部门ID","部门","业务日期","场所ID","场所","状态","已结束班次","签到","签退","迟到分钟","早退分钟","有效工时分钟","请假分钟","加班分钟","审批来源ID"));
        for(var r:rows)line(csv,Arrays.asList(r.userId(),r.employeeNo(),r.employeeName(),r.companyId(),r.departmentId(),r.departmentName(),r.date(),r.workplaceId(),r.workplaceName(),r.status(),r.finalized(),r.checkIn(),r.checkOut(),r.lateMinutes(),r.earlyMinutes(),r.workMinutes(),r.leaveMinutes(),r.overtimeMinutes(),r.approvalIds().toString()));
        return csv.toString();
    }
    static String cell(Object value){
        String text=value==null?"":value.toString();
        // Spreadsheet import may ignore leading spaces/control characters before a formula marker.
        String probe=text.stripLeading();
        if(text.chars().anyMatch(c->c<32 || c==127) || (!probe.isEmpty() && "=+-@".indexOf(probe.charAt(0))>=0))text="'"+text;
        return "\""+text.replace("\"","\"\"")+"\"";
    }
    private static void line(StringBuilder out,List<?> values){out.append(String.join(",",values.stream().map(AttendanceReports::cell).toList())).append("\r\n");}
    private Snapshot read(Authentication actor,Filter f,Instant now){
        identity.current(actor);scope.manager(actor);
        if(f==null || f.from()==null || f.to()==null || f.to().isBefore(f.from()) || ChronoUnit.DAYS.between(f.from(),f.to())>93 || f.from().getYear()<1000 || f.to().getYear()>9999)throw BusinessException.invalid("查询日期范围最多94天，年份1000至9999");
        Long company=scope.companyFilter(actor,f.companyId());
        if(f.departmentId()!=null){var dep=organizations.department(f.departmentId());if(dep==null)throw BusinessException.missing();scope.company(actor,dep.companyId());if(company!=null && !company.equals(dep.companyId()))throw BusinessException.forbidden();company=dep.companyId();}
        if(f.workplaceId()!=null){var place=places.find(f.workplaceId());if(place==null)throw BusinessException.missing();scope.company(actor,place.companyId());if(company!=null && !company.equals(place.companyId()))throw BusinessException.forbidden();company=place.companyId();}
        var selected=new ArrayList<EmployeeView>();
        if(f.userId()!=null){var user=employees.get(actor,f.userId());if(company!=null && !company.equals(user.companyId()))throw BusinessException.forbidden();if(f.departmentId()!=null && !f.departmentId().equals(user.departmentId()))throw BusinessException.invalid("员工不属于所选部门");if(user.enabled() && user.companyId()!=null)selected.add(user);}
        else{
            var first=employees.list(actor,company,"",1,100);if(first.total()>1000)throw BusinessException.invalid("员工范围超过1000，请按公司或员工缩小范围");
            for(int p=1;p<=Math.max(1,(first.total()+99)/100);p++){
                var batch=p==1?first:employees.list(actor,company,"",p,100);
                for(var u:batch.items())if(u.enabled() && u.companyId()!=null && (f.departmentId()==null || f.departmentId().equals(u.departmentId()))){selected.add(u);if(selected.size()>100)throw BusinessException.invalid("单次报表最多100名在职员工，请缩小部门或员工范围");}
            }
        }
        var rows=new ArrayList<Row>();var locations=new TreeMap<Long,WorkplaceView>();
        for(var user:selected){
            var shifts=new HashMap<LocalDate,AttendanceSchedules.Schedule>();for(var s:schedules.list(user.userId(),f.from(),f.to()))shifts.put(s.businessDate(),s);
            for(var d:days.list(actor,user.userId(),f.from(),f.to())){
                if(f.workplaceId()!=null && f.workplaceId()!=d.workplaceId())continue;
                var place=locations.computeIfAbsent(d.workplaceId(),places::find);
                // A transfer must not expose another company's location through a historical schedule.
                if(place==null || !Objects.equals(place.companyId(),user.companyId()))throw BusinessException.conflict("历史排班场所与当前员工公司不一致，请联系管理员核实后查询");
                scope.company(actor,place.companyId());var s=shifts.get(d.businessDate());
                boolean finished=!LocalDateTime.ofInstant(now,AttendanceRules.ZONE).isBefore(AttendanceRules.finish(s.businessDate(),s.rule().start(),s.rule().end()));
                var a=d.attendance();rows.add(new Row(user.userId(),user.employeeNo(),user.displayName(),user.companyId(),user.departmentId(),user.departmentName(),d.businessDate(),d.workplaceId(),place.name(),a.status(),finished,d.checkIn(),d.checkOut(),a.lateMinutes(),a.earlyMinutes(),a.workMinutes(),d.leaveMinutes(),d.overtimeMinutes(),List.copyOf(d.approvalIds())));
            }
        }
        rows.sort(Comparator.comparing(Row::date).thenComparingLong(Row::userId));
        var heat=new ArrayList<Heat>();for(var place:locations.values()){
            var group=rows.stream().filter(r->r.workplaceId()==place.id()).toList();
            heat.add(new Heat(place.id(),place.name(),place.latitude(),place.longitude(),place.coordinateSystem(),group.size(),group.stream().filter(r->r.checkIn()!=null || r.checkOut()!=null).count(),group.stream().mapToLong(Row::workMinutes).sum()));
        }
        return new Snapshot(List.copyOf(rows),selected.size(),List.copyOf(heat));
    }
    static List<Hour> hours(List<Row> rows){
        long[] ins=new long[24],outs=new long[24];
        for(var row:rows){if(row.checkIn()!=null)ins[row.checkIn().getHour()]++;if(row.checkOut()!=null)outs[row.checkOut().getHour()]++;}
        var result=new ArrayList<Hour>();for(int hour=0;hour<24;hour++)result.add(new Hour(hour,ins[hour],outs[hour]));return List.copyOf(result);
    }
    static Totals total(List<Row> rows){
        long pending=0,expected=0,attended=0,late=0,early=0,absent=0,missing=0,leave=0,rest=0,work=0,lm=0,om=0;
        for(var r:rows){
            work+=r.workMinutes();lm+=r.leaveMinutes();om+=r.overtimeMinutes();
            if(!r.finalized()){pending++;continue;}
            if(r.status().equals("REST_DAY")){rest++;continue;}
            if(r.status().equals("ON_LEAVE")){leave++;continue;}
            if(r.status().equals("PENDING")){pending++;continue;}
            expected++;if(Set.of("NORMAL","LATE","EARLY","LATE_AND_EARLY").contains(r.status()))attended++;
            if(r.lateMinutes()>0)late++;if(r.earlyMinutes()>0)early++;if(r.status().equals("ABSENT"))absent++;if(r.status().equals("MISSING_PUNCH"))missing++;
        }
        BigDecimal rate=expected==0?null:BigDecimal.valueOf(attended*100).divide(BigDecimal.valueOf(expected),2,RoundingMode.HALF_UP);
        return new Totals(rows.size(),pending,expected,attended,rate,late,early,absent,missing,leave,rest,work,lm,om);
    }
}
