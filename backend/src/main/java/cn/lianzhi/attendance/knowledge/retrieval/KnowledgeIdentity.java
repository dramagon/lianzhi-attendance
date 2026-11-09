package cn.lianzhi.attendance.knowledge.retrieval;

import cn.lianzhi.attendance.common.BusinessException;
import cn.lianzhi.attendance.employee.EmployeeService;
import cn.lianzhi.attendance.organization.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** Authentication-to-C bridge; never binds identity or roles from a request/model. */
@Service
public class KnowledgeIdentity {
    private final DataScope scope;
    private final EmployeeService employees;
    private final OrganizationMapper organizations;
    public KnowledgeIdentity(DataScope scope, EmployeeService employees, OrganizationMapper organizations) {
        this.scope=scope; this.employees=employees; this.organizations=organizations;
    }
    public KnowledgeActor current(Authentication authentication) {
        var account=scope.actor(authentication);
        var employee=employees.get(authentication,account.id());
        if (employee.companyId()!=null) {
            var company=organizations.company(employee.companyId());
            if (company==null || !Boolean.TRUE.equals(company.enabled())) throw BusinessException.forbidden();
        } else if (!account.role().equals("ADMIN")) throw BusinessException.forbidden();
        return new KnowledgeActor(account.id(),KnowledgeActor.Role.valueOf(account.role()),employee.companyId());
    }
}
