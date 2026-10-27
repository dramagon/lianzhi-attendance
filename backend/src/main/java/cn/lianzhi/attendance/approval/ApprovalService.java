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

@Service
public class ApprovalService {
    private final EmployeeService employees;
    private final DataScope scope;
    private final OrganizationMapper organizations;
    private final WorkplaceService workplaces;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<ApprovalAttendancePort> attendance;
    public ApprovalService(EmployeeService employees,DataScope scope,OrganizationMapper organizations,WorkplaceService workplaces,JdbcTemplate jdbc,ObjectProvider<ApprovalAttendancePort> attendance) {
        this.employees=employees; this.scope=scope; this.organizations=organizations; this.workplaces=workplaces; this.jdbc=jdbc; this.attendance=attendance;
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
        blockers.add("当前仅支持内容校验，申请保存和审批处理尚未开放");
        return new Readiness(false,true,"POLICY_ONLY","Asia/Shanghai",v3,v4,adjustment,List.copyOf(blockers));
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
        if (draft.type()==ApprovalRules.Type.MISSING_PUNCH && workplaces.activeForEmployee(actor,employee.userId()).stream().noneMatch(place->place.id().equals(draft.workplaceId()))) {
            throw BusinessException.invalid("补卡场所须为本人当前有效绑定场所");
        }
        return new Preview(employee.userId(),employee.displayName(),employee.companyId(),employee.departmentId(),draft,ApprovalRules.requestedMinutes(draft),true,false,false);
    }
    public void requireStorage() {
        throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"申请保存、记录查询及审批处理尚未接入；当前仅支持内容校验");
    }
}
