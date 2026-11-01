package cn.lianzhi.attendance.assistant;

import cn.lianzhi.attendance.attendance.AttendanceDays;
import cn.lianzhi.attendance.common.*;
import cn.lianzhi.attendance.employee.*;
import cn.lianzhi.attendance.organization.*;
import cn.lianzhi.attendance.knowledge.retrieval.KnowledgeIdentity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** B computes each day; C only adds already-authorized numeric fields, never creates daily statuses. */
@Service
public class AssistantStatistics {
    private final DataScope scope;private final EmployeeService employees;private final OrganizationMapper organizations;
    private final AttendanceDays days;private final KnowledgeIdentity identity;
    public AssistantStatistics(DataScope scope,EmployeeService employees,OrganizationMapper organizations,AttendanceDays days,KnowledgeIdentity identity){
        this.scope=scope;this.employees=employees;this.organizations=organizations;this.days=days;this.identity=identity;
    }
    public record Summary(Long companyId,Long departmentId,LocalDate from,LocalDate to,int employeeCount,long scheduledDays,
                          long pendingDays,long workMinutes,long leaveMinutes,long overtimeMinutes,Map<String,Long> statuses,
                          List<Long> sourceUserIds,String source){public Summary{statuses=Map.copyOf(statuses);sourceUserIds=List.copyOf(sourceUserIds);}}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Summary summarize(Authentication actor,Long companyId,Long departmentId,LocalDate from,LocalDate to){
        identity.current(actor);scope.manager(actor);Long filter=scope.companyFilter(actor,companyId);
        if(from==null || to==null || to.isBefore(from) || ChronoUnit.DAYS.between(from,to)>93)throw BusinessException.invalid("查询日期范围最多94天");
        if(departmentId!=null){
            var department=organizations.department(departmentId);if(department==null)throw BusinessException.missing();
            scope.company(actor,department.companyId());
            if(filter!=null && !filter.equals(department.companyId()))throw BusinessException.forbidden();
            filter=department.companyId();
        }
        var first=employees.list(actor,filter,"",1,100);
        if(first.total()>1000)throw BusinessException.invalid("员工范围过大，请缩小公司范围");
        var selected=new ArrayList<EmployeeView>();
        for(int page=1;page<=Math.max(1,(first.total()+99)/100);page++){
            var batch=page==1?first:employees.list(actor,filter,"",page,100);
            for(var employee:batch.items())if(employee.enabled() && employee.companyId()!=null && (departmentId==null || departmentId.equals(employee.departmentId()))){
                selected.add(employee);if(selected.size()>100)throw BusinessException.invalid("单次统计最多100名在职员工，请缩小公司或部门范围");
            }
        }
        long count=0,pending=0,work=0,leave=0,overtime=0;var statuses=new TreeMap<String,Long>();var ids=new ArrayList<Long>();
        for(var employee:selected){
            ids.add(employee.userId());
            for(var day:days.list(actor,employee.userId(),from,to)){
                count++;String status=day.attendance().status();if(status.equals("PENDING"))pending++;
                statuses.merge(status,1L,Long::sum);work+=day.attendance().workMinutes();leave+=day.leaveMinutes();overtime+=day.overtimeMinutes();
            }
        }
        return new Summary(filter,departmentId,from,to,selected.size(),count,pending,work,leave,overtime,statuses,ids,"AttendanceDays.list");
    }
}
