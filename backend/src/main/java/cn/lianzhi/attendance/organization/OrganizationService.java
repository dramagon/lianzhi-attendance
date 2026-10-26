package cn.lianzhi.attendance.organization;

import cn.lianzhi.attendance.common.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

@Service
public class OrganizationService {
    private final OrganizationMapper mapper;
    private final DataScope scope;
    public OrganizationService(OrganizationMapper mapper, DataScope scope) { this.mapper = mapper; this.scope = scope; }
    public PageResult<OrganizationMapper.Company> companies(Authentication actor, Long companyId, int page, int size) {
        scope.manager(actor); PageResult.validate(page,size);
        Long filter = scope.companyFilter(actor,companyId);
        return new PageResult<>(mapper.companies(filter,size,(page-1)*size),mapper.countCompanies(filter),page,size);
    }
    public PageResult<OrganizationMapper.Department> departments(Authentication actor, Long companyId, int page, int size) {
        scope.manager(actor); PageResult.validate(page,size);
        Long filter = scope.companyFilter(actor,companyId);
        return new PageResult<>(mapper.departments(filter,size,(page-1)*size),mapper.countDepartments(filter),page,size);
    }
    public void activeCompany(Long id) {
        Inputs.required(id,"公司");
        if (mapper.lockCompany(id) == null) throw BusinessException.missing();
        if (!mapper.company(id).enabled()) throw BusinessException.conflict("公司已停用");
    }
    public void activeDepartment(Long id, Long companyId) {
        if (id == null) return;
        var department = mapper.department(id);
        if (department == null || !Objects.equals(department.companyId(),companyId) || !department.enabled()) throw BusinessException.invalid("部门不存在、已停用或不属于所选公司");
    }
    @Transactional
    public void company(Authentication actor, Long id, NameInput input) {
        scope.admin(actor); String name = Inputs.text(input.name(),100,"公司名称"); Inputs.required(input.enabled(),"启用状态");
        if (id == null) { mapper.insertCompany(name,input.enabled()); return; }
        if (mapper.lockCompany(id) == null) throw BusinessException.missing();
        if (!input.enabled() && mapper.companyReferences(id)>0) throw BusinessException.conflict("请先停用或迁移公司的员工、部门和场所");
        mapper.updateCompany(id,name,input.enabled());
    }
    @Transactional
    public void department(Authentication actor, Long id, DepartmentInput input) {
        scope.manager(actor); scope.company(actor,input.companyId());
        var old = id==null?null:mapper.department(id);
        if (id!=null && old==null) throw BusinessException.missing();
        if (old!=null) scope.company(actor,old.companyId());
        if (input.companyId()!=null) activeCompany(input.companyId());
        else if (old==null || old.companyId()!=null) throw BusinessException.invalid("请选择公司");
        String name = Inputs.text(input.name(),100,"部门名称"); Inputs.required(input.enabled(),"启用状态");
        if (id == null) { mapper.insertDepartment(input.companyId(),name,input.enabled()); return; }
        if (!Objects.equals(old.companyId(),input.companyId())) throw BusinessException.invalid("部门不能直接迁移公司，请新建部门后调整员工归属");
        if (!input.enabled() && mapper.departmentReferences(id)>0) throw BusinessException.conflict("部门仍有启用员工");
        mapper.updateDepartment(id,name,input.enabled());
    }
    public record NameInput(String name, Boolean enabled) {}
    public record DepartmentInput(Long companyId, String name, Boolean enabled) {}
}
