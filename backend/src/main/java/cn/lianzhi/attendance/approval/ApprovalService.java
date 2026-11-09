package cn.lianzhi.attendance.approval;

import cn.lianzhi.attendance.common.*;
import cn.lianzhi.attendance.employee.*;
import cn.lianzhi.attendance.organization.*;
import cn.lianzhi.attendance.workplace.WorkplaceService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import java.util.*;
import java.time.*;
import org.springframework.transaction.annotation.*;

@Service
public class ApprovalService {
    private final EmployeeService employees;
    private final DataScope scope;
    private final OrganizationMapper organizations;
    private final WorkplaceService workplaces;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<ApprovalAttendancePort> attendance;
    private final ApprovalStore store;
    public ApprovalService(EmployeeService employees,DataScope scope,OrganizationMapper organizations,WorkplaceService workplaces,JdbcTemplate jdbc,ObjectProvider<ApprovalAttendancePort> attendance,ApprovalStore store) {
        this.employees=employees; this.scope=scope; this.organizations=organizations; this.workplaces=workplaces; this.jdbc=jdbc; this.attendance=attendance;this.store=store;
    }
    public record Readiness(boolean available,boolean validationAvailable,String phase,String timeZone,boolean attendanceSchemaReady,boolean approvalSchemaReady,boolean adjustmentReady,List<String> blockers) {}
    public Readiness status() {
        boolean v3=migrated("3"),v4=migrated("4");
        var providers=attendance.stream().toList();
        boolean adjustment=providers.size()==1 && providers.get(0).available();
        var blockers=new ArrayList<String>();
        if (!v3) blockers.add("考勤与真实排班数据尚未接通");
        if (!v4) blockers.add("审批存储尚未启用");
        if (!adjustment) blockers.add("审批结果的考勤调整服务尚未接入");
        boolean ready=v3 && v4 && adjustment;
        return new Readiness(ready,true,ready?"CONNECTED":"DEPENDENCY_UNAVAILABLE","Asia/Shanghai",v3,v4,adjustment,List.copyOf(blockers));
    }
    private boolean migrated(String version) {
        try { return jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version=? AND success=TRUE",Long.class,version)>0; }
        catch (DataAccessException error) { return false; }
    }
    public record Preview(long applicantId,String applicantName,Long companyId,Long departmentId,ApprovalRules.Draft draft,long requestedMinutes,boolean validated,boolean stored,boolean attendanceAdjusted) {}
    public Preview preview(Authentication actor,ApprovalRules.Draft input) {
        EmployeeView employee=employees.get(actor,scope.actor(actor).id());
        if (employee.companyId()==null) throw BusinessException.invalid("请先由管理员分配公司后再填写申请");
        var company=organizations.company(employee.companyId());
        if (company==null || !company.enabled()) throw BusinessException.conflict("所属公司已停用，暂不能申请");
        var draft=ApprovalRules.validate(input);
        if(draft.type()==ApprovalRules.Type.MISSING_PUNCH && draft.startAt().isAfter(now())) throw BusinessException.invalid("补卡时间不能晚于当前北京时间");
        if (draft.type()==ApprovalRules.Type.MISSING_PUNCH && workplaces.activeForEmployee(actor,employee.userId()).stream().noneMatch(place->place.id().equals(draft.workplaceId()))) {
            throw BusinessException.invalid("补卡场所须为本人当前有效绑定场所");
        }
        return new Preview(employee.userId(),employee.displayName(),employee.companyId(),employee.departmentId(),draft,ApprovalRules.requestedMinutes(draft),true,false,false);
    }
    public void requireStorage() {
        if(!status().available())throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"审批依赖暂不可用，请稍后重试");
    }
    public record Submission(String requestKey,ApprovalRules.Draft draft) {}
    public record Decision(ApprovalRules.Action action,long expectedVersion,String note) {}
    public record Detail(ApprovalStore.Entry entry,List<ApprovalRules.Transition> history) {}
    private LocalDateTime now(){return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0);}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public ApprovalStore.Entry create(Authentication actor,Submission input) {
        requireStorage(); Inputs.required(input,"申请内容");
        String key=Inputs.text(input.requestKey(),36,"请求编号");
        try{if(!UUID.fromString(key).toString().equals(key))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw BusinessException.invalid("请求编号须为标准UUID");}
        long user=scope.actor(actor).id();store.lockEmployee(user);
        var draft=ApprovalRules.validate(input.draft());
        var existing=store.byKey(user,key);
        if(existing!=null){if(!existing.request().draft().equals(draft))throw BusinessException.conflict("同一请求编号不能提交不同内容");return existing;}
        var checked=preview(actor,draft);
        ApprovalRules.requireNoOverlap(user,draft,store.active(user));
        return store.insert(user,checked.companyId(),checked.departmentId(),key,draft,now());
    }
    private void readable(Authentication actor,ApprovalStore.Entry entry) {
        var user=scope.actor(actor);var r=entry.request();
        if(user.id()==r.applicantId())return;
        scope.manager(actor);scope.company(actor,r.companyId());employees.get(actor,r.applicantId());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Detail detail(Authentication actor,long id){requireStorage();var entry=store.get(id);readable(actor,entry);return new Detail(entry,store.history(id));}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResult<ApprovalStore.Entry> list(Authentication actor,boolean managed,String state,int page,int size){
        requireStorage();PageResult.validate(page,size);
        if(!Set.of("ALL","PROCESSED","PENDING","APPROVED","REJECTED","WITHDRAWN").contains(state))throw BusinessException.invalid("申请状态不正确");
        if(managed){scope.manager(actor);return store.list(null,scope.companyFilter(actor,null),state,page,size);}
        return store.list(scope.actor(actor).id(),null,state,page,size);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Detail decide(Authentication actor,long id,Decision input){
        requireStorage();Inputs.required(input,"处理内容");Inputs.required(input.action(),"操作");
        var entry=store.get(id);readable(actor,entry);
        // Same lock order as B: employee before application. Serializes creates, decisions and punches.
        store.lockEmployee(entry.request().applicantId());store.lockRequest(id);entry=store.get(id);readable(actor,entry);
        var r=entry.request();var user=scope.actor(actor);
        var identity=new ApprovalRules.Actor(user.id(),user.companyId(),user.role());
        if(input.action()!=ApprovalRules.Action.WITHDRAW){
            scope.manager(actor);if(user.id()==r.applicantId())throw BusinessException.forbidden();
            scope.company(actor,r.companyId());var applicant=employees.get(actor,r.applicantId());
            if(!Objects.equals(applicant.companyId(),r.companyId()))throw BusinessException.conflict("申请人组织归属已改变，请本人撤回后重新提交");
        }
        // Exact retry returns the original result without a second audit or adjustment.
        var history=store.history(id);
        var target=switch(input.action()){case APPROVE->ApprovalRules.State.APPROVED;case REJECT->ApprovalRules.State.REJECTED;case WITHDRAW->ApprovalRules.State.WITHDRAWN;};
        String note=input.note()==null?"":input.note().trim();
        if(r.state()==target && r.version()==input.expectedVersion()+1 && !history.isEmpty()){
            var last=history.get(history.size()-1);
            if(last.actorId()==user.id() && last.reason().equals(note))return new Detail(entry,history);
        }
        var result=ApprovalRules.transition(r,input.action(),identity,now(),input.note(),input.expectedVersion());
        if(input.action()==ApprovalRules.Action.APPROVE){
            // Recheck applicant organization and active binding at approval time, under the same lock.
            var applicant=employees.get(actor,r.applicantId());
            var company=organizations.company(r.companyId());
            if(!applicant.enabled() || company==null || !company.enabled())throw BusinessException.conflict("申请人或公司已停用");
            if(r.draft().type()==ApprovalRules.Type.MISSING_PUNCH && workplaces.activeForEmployee(actor,r.applicantId()).stream().noneMatch(p->p.id().equals(r.draft().workplaceId())))throw BusinessException.conflict("申请人的场所绑定已失效");
        }
        store.change(result);
        if(input.action()==ApprovalRules.Action.APPROVE){
            var port=attendance.getObject();
            var adjustment=new ApprovalAttendancePort.Adjustment(id,r.applicantId(),r.companyId(),r.draft(),user.id(),result.transition().occurredAt());
            try{port.validate(actor,adjustment);port.apply(actor,adjustment);}
            catch(IllegalArgumentException e){throw BusinessException.invalid(e.getMessage());}
            catch(cn.lianzhi.attendance.attendance.AttendanceProblem e){throw new BusinessException(e.status(),e.getMessage());}
        }
        return new Detail(store.get(id),store.history(id));
    }
}
