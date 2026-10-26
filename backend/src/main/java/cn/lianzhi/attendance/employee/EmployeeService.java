package cn.lianzhi.attendance.employee;

import cn.lianzhi.attendance.common.*;
import cn.lianzhi.attendance.identity.AccountMapper;
import cn.lianzhi.attendance.organization.*;
import cn.lianzhi.attendance.workplace.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class EmployeeService {
    private final EmployeeMapper mapper;
    private final AccountMapper accounts;
    private final WorkplaceMapper workplaces;
    private final OrganizationService organizations;
    private final DataScope scope;
    private final PasswordEncoder passwords;
    public EmployeeService(EmployeeMapper mapper, AccountMapper accounts, WorkplaceMapper workplaces, OrganizationService organizations, DataScope scope, PasswordEncoder passwords) {
        this.mapper=mapper; this.accounts=accounts; this.workplaces=workplaces; this.organizations=organizations; this.scope=scope; this.passwords=passwords;
    }
    public PageResult<EmployeeView> list(Authentication actor, Long companyId, String query, int page, int size) {
        PageResult.validate(page,size);
        if (query==null || query.length()>100) throw BusinessException.invalid("搜索词不能超过100字");
        var user=scope.actor(actor);
        Long ownId=user.role().equals("EMPLOYEE") ? user.id() : null;
        Long filter=ownId!=null ? user.companyId() : scope.companyFilter(actor,companyId);
        if (ownId!=null && companyId!=null && !companyId.equals(user.companyId())) throw BusinessException.forbidden();
        String search=query.isBlank()?"":"%"+query.trim().toLowerCase(Locale.ROOT)+"%";
        return new PageResult<>(mapper.list(filter,ownId,search,size,(page-1)*size),mapper.count(filter,ownId,search),page,size);
    }
    public EmployeeView get(Authentication actor, Long id) {
        var employee=mapper.find(id); if (employee==null) throw BusinessException.missing();
        scope.employee(actor,id,employee.companyId()); return employee;
    }
    public List<WorkplaceView> workplaces(Authentication actor, Long id) {
        get(actor,id); return workplaces.forEmployee(id);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public EmployeeView save(Authentication actor, Long id, EmployeeInput raw) {
        scope.manager(actor);
        String username=Inputs.text(raw.username(),50,"账号");
        if (id==null && !username.matches("[a-zA-Z0-9_.-]{3,50}")) throw BusinessException.invalid("新增账号须为3～50位字母、数字、点、横线或下划线");
        String name=Inputs.text(raw.displayName(),100,"姓名"), number=Inputs.text(raw.employeeNo(),30,"工号");
        if (raw.role()==null || !Set.of("ADMIN","HR","EMPLOYEE").contains(raw.role())) throw BusinessException.invalid("角色不正确");
        Inputs.required(raw.enabled(),"启用状态"); Inputs.required(raw.workplaceIds(),"绑定场所列表");
        if (raw.workplaceIds().size()>100 || raw.workplaceIds().stream().anyMatch(Objects::isNull) || new HashSet<>(raw.workplaceIds()).size()!=raw.workplaceIds().size()) throw BusinessException.invalid("场所不得重复、为空或超过100个");
        // 所有员工写请求先按同一顺序锁定账号，串行保护最后一个管理员，再锁公司。
        mapper.lockUsers();
        scope.manager(actor);
        var old=id==null?null:get(actor,id);
        var operator=scope.actor(actor);
        if (operator.role().equals("HR") && (!raw.role().equals("EMPLOYEE") || (old!=null && !old.role().equals("EMPLOYEE")))) throw BusinessException.forbidden();
        if (old!=null && !old.username().equals(username)) throw BusinessException.invalid("登录账号不能修改");
        if (old!=null && old.enabled() && old.role().equals("ADMIN") && (!raw.enabled() || !raw.role().equals("ADMIN")) && mapper.enabledAdmins()<=1) throw BusinessException.conflict("必须保留至少一个启用的系统管理员");
        scope.company(actor,raw.companyId());
        if (raw.companyId()!=null) {
            organizations.activeCompany(raw.companyId()); organizations.activeDepartment(raw.departmentId(),raw.companyId());
        } else if (!raw.role().equals("ADMIN") || raw.departmentId()!=null || !raw.workplaceIds().isEmpty()) throw BusinessException.invalid("非平台管理员必须选择公司");
        for (Long placeId:raw.workplaceIds()) {
            var place=workplaces.find(placeId);
            if (place==null || !place.enabled() || !Objects.equals(place.companyId(),raw.companyId())) throw BusinessException.invalid("绑定场所不存在、已停用或属于其他公司");
        }
        String hash=null;
        if (id==null || (raw.password()!=null && !raw.password().isEmpty())) {
            if (raw.password()==null || raw.password().length()<12 || raw.password().getBytes(StandardCharsets.UTF_8).length>72) throw BusinessException.invalid("密码至少12位，UTF-8编码不超过72字节");
            hash=passwords.encode(raw.password());
        }
        var input=new EmployeeInput(username,name,number,raw.role(),raw.companyId(),raw.departmentId(),raw.enabled(),null,raw.workplaceIds());
        if (id==null) { mapper.insert(username,hash,input); id=accounts.findByUsername(username).id(); }
        else mapper.update(id,hash,input);
        mapper.clearWorkplaces(id);
        for (Long placeId:raw.workplaceIds()) mapper.bind(id,placeId);
        return mapper.find(id);
    }
    public record EmployeeInput(String username,String displayName,String employeeNo,String role,Long companyId,Long departmentId,Boolean enabled,String password,List<Long> workplaceIds) {}
}
