package cn.lianzhi.attendance.workplace;

import cn.lianzhi.attendance.common.*;
import cn.lianzhi.attendance.organization.*;
import cn.lianzhi.attendance.employee.EmployeeService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

@Service
public class WorkplaceService {
    private final WorkplaceMapper mapper;
    private final OrganizationService organizations;
    private final DataScope scope;
    private final EmployeeService employees;
    public WorkplaceService(WorkplaceMapper mapper, OrganizationService organizations, DataScope scope, EmployeeService employees) { this.mapper=mapper; this.organizations=organizations; this.scope=scope; this.employees=employees; }
    public PageResult<WorkplaceView> list(Authentication actor, Long companyId, int page, int size) {
        PageResult.validate(page,size);
        var user = scope.actor(actor);
        Long ownId = user.role().equals("EMPLOYEE") ? user.id() : null;
        Long filter = scope.companyFilter(actor,companyId);
        return new PageResult<>(mapper.list(filter,ownId,size,(page-1)*size),mapper.count(filter,ownId),page,size);
    }
    /** B调用时只使用有效绑定；坐标必须先统一到返回的coordinateSystem再计算距离。 */
    public List<WorkplaceView> activeForEmployee(Authentication actor, Long userId) {
        var employee=employees.get(actor,userId);
        if (!employee.enabled() || employee.companyId()==null) return List.of();
        return mapper.list(employee.companyId(),userId,100,0);
    }
    @Transactional
    public void save(Authentication actor, Long id, WorkplaceInput raw) {
        scope.manager(actor); scope.company(actor,raw.companyId()); organizations.activeCompany(raw.companyId());
        String name=Inputs.text(raw.name(),100,"场所名称");
        Inputs.required(raw.enabled(),"启用状态"); Inputs.required(raw.latitude(),"纬度"); Inputs.required(raw.longitude(),"经度"); Inputs.required(raw.radiusMeters(),"打卡半径");
        if (raw.latitude().abs().compareTo(BigDecimal.valueOf(90))>0 || raw.longitude().abs().compareTo(BigDecimal.valueOf(180))>0 || raw.latitude().scale()>7 || raw.longitude().scale()>7) throw BusinessException.invalid("经纬度越界或超过7位小数");
        if (raw.coordinateSystem()==null || !Set.of("WGS84","GCJ02","BD09").contains(raw.coordinateSystem())) throw BusinessException.invalid("坐标系须为WGS84、GCJ02或BD09");
        if (raw.radiusMeters()<10 || raw.radiusMeters()>10000) throw BusinessException.invalid("半径须为10～10000米");
        var input=new WorkplaceInput(raw.companyId(),name,raw.latitude(),raw.longitude(),raw.coordinateSystem(),raw.radiusMeters(),raw.enabled());
        if (id==null) { mapper.insert(input); return; }
        var old=mapper.find(id); if (old==null) throw BusinessException.missing();
        scope.company(actor,old.companyId());
        if (!Objects.equals(old.companyId(),input.companyId())) throw BusinessException.invalid("场所不能直接迁移公司");
        if (!input.enabled() && mapper.activeReferences(id)>0) throw BusinessException.conflict("场所仍绑定启用员工，请先调整员工场所");
        mapper.update(id,input);
    }
    public record WorkplaceInput(Long companyId,String name,BigDecimal latitude,BigDecimal longitude,String coordinateSystem,Integer radiusMeters,Boolean enabled) {}
}
