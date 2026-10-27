package cn.lianzhi.attendance;

import cn.lianzhi.attendance.employee.EmployeeService;
import cn.lianzhi.attendance.workplace.WorkplaceService;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrganizationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired EmployeeService employees;
    @Autowired WorkplaceService workplaces;
    Long companyId,departmentId,placeId,foreignCompanyId,employeeId;

    @BeforeEach void fixture() {
        companyId=jdbc.queryForObject("SELECT company_id FROM sys_user WHERE username='employee'",Long.class);
        departmentId=jdbc.queryForObject("SELECT department_id FROM sys_user WHERE username='employee'",Long.class);
        employeeId=jdbc.queryForObject("SELECT id FROM sys_user WHERE username='employee'",Long.class);
        jdbc.update("INSERT INTO org_company(name) VALUES('第二公司')");
        foreignCompanyId=jdbc.queryForObject("SELECT id FROM org_company WHERE name='第二公司'",Long.class);
        jdbc.update("INSERT INTO workplace(company_id,name,latitude,longitude,coordinate_system,radius_meters) VALUES(?,'测试场所',31.2,121.5,'WGS84',100)",companyId);
        placeId=jdbc.queryForObject("SELECT id FROM workplace WHERE name='测试场所'",Long.class);
    }
    private Map<String,Object> employee(String username,String role,Long company) {
        Map<String,Object> body=new HashMap<>();
        body.put("username",username); body.put("displayName","测试员工"); body.put("employeeNo","TEST-"+username);
        body.put("role",role); body.put("companyId",company); body.put("departmentId",company.equals(companyId)?departmentId:null);
        body.put("enabled",true); body.put("password","TestPassword123!"); body.put("workplaceIds",List.of());
        return body;
    }
    private ResultActions write(String method,String path,Object body,String actor) throws Exception {
        var request=method.equals("POST")?post(path):put(path);
        return mvc.perform(request.with(user(actor)).with(csrf()).contentType("application/json").content(json.writeValueAsString(body)));
    }
    private MockHttpSession login(String username) throws Exception {
        var csrf=mvc.perform(get("/api/auth/csrf")).andReturn();
        String token=json.readTree(csrf.getResponse().getContentAsString()).path("data").path("token").asText();
        return (MockHttpSession)mvc.perform(post("/api/auth/login").session((MockHttpSession)csrf.getRequest().getSession()).header("X-CSRF-TOKEN",token).param("username",username).param("password","Lianzhi@2026"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession();
    }
    @Test void createReadUpdateAndBindingArePersistedWithoutSecrets() throws Exception {
        var body=employee("newuser","EMPLOYEE",companyId); body.put("workplaceIds",List.of(placeId));
        var created=write("POST","/api/employees",body,"admin").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist()).andExpect(jsonPath("$.data.password").doesNotExist()).andReturn();
        long id=json.readTree(created.getResponse().getContentAsString()).path("data").path("userId").asLong();
        mvc.perform(get("/api/employees/"+id+"/workplaces").with(user("hr"))).andExpect(jsonPath("$.data[0].id").value(placeId));
        body.put("enabled",false); body.put("password","");
        write("PUT","/api/employees/"+id,body,"hr").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT enabled FROM sys_user WHERE id=?",Boolean.class,id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE id=?",String.class,id)).startsWith("$2");
    }
    @Test void hrCannotCrossCompanyOrGrantRoles() throws Exception {
        write("POST","/api/employees",employee("foreign","EMPLOYEE",foreignCompanyId),"hr").andExpect(status().isForbidden());
        write("POST","/api/employees",employee("elevated","ADMIN",companyId),"hr").andExpect(status().isForbidden());
        mvc.perform(get("/api/employees").param("companyId",foreignCompanyId.toString()).with(user("hr"))).andExpect(status().isForbidden());
        write("POST","/api/organizations/companies",Map.of("name","越权公司","enabled",true),"hr").andExpect(status().isForbidden());
        write("POST","/api/workplaces",Map.of("companyId",foreignCompanyId,"name","越权场所","latitude",30,"longitude",120,"coordinateSystem","WGS84","radiusMeters",100,"enabled",true),"hr").andExpect(status().isForbidden());
    }
    @Test void employeeOnlySeesSelfAndBoundPlaces() throws Exception {
        mvc.perform(get("/api/employees").with(user("employee"))).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].userId").value(employeeId));
        Long adminId=jdbc.queryForObject("SELECT id FROM sys_user WHERE username='admin'",Long.class);
        mvc.perform(get("/api/employees/"+adminId).with(user("employee"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/workplaces").with(user("employee"))).andExpect(jsonPath("$.data.total").value(0));
        jdbc.update("INSERT INTO employee_workplace(user_id,workplace_id) VALUES(?,?)",employeeId,placeId);
        mvc.perform(get("/api/workplaces").with(user("employee"))).andExpect(jsonPath("$.data.total").value(1));
        write("POST","/api/employees",employee("attack","EMPLOYEE",companyId),"employee").andExpect(status().isForbidden());
    }
    @Test void crossCompanyDepartmentAndWorkplaceAreRejectedAndRollback() throws Exception {
        var body=employee("badbinding","EMPLOYEE",foreignCompanyId); body.put("workplaceIds",List.of(placeId));
        write("POST","/api/employees",body,"admin").andExpect(status().isBadRequest());
        body.put("workplaceIds",List.of()); body.put("departmentId",departmentId);
        write("POST","/api/employees",body,"admin").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username='badbinding'",Long.class)).isZero();
    }
    @Test void lastAdminCannotBeDisabledOrDemoted() throws Exception {
        Long id=jdbc.queryForObject("SELECT id FROM sys_user WHERE username='admin'",Long.class);
        var body=employee("admin","EMPLOYEE",companyId);
        write("PUT","/api/employees/"+id,body,"admin").andExpect(status().isConflict());
        body.put("role","ADMIN"); body.put("enabled",false);
        write("PUT","/api/employees/"+id,body,"admin").andExpect(status().isConflict());
    }
    @Test void disablingOrChangingRoleRevokesExistingSession() throws Exception {
        MockHttpSession session=login("employee");
        var body=employee("employee","EMPLOYEE",companyId); body.put("enabled",false);
        write("PUT","/api/employees/"+employeeId,body,"admin").andExpect(status().isOk());
        mvc.perform(get("/api/auth/session").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
        MockHttpSession hrSession=login("hr");
        Long hrId=jdbc.queryForObject("SELECT id FROM sys_user WHERE username='hr'",Long.class);
        var hr=employee("hr","EMPLOYEE",companyId);
        write("PUT","/api/employees/"+hrId,hr,"admin").andExpect(status().isOk());
        mvc.perform(get("/api/management/overview").session(hrSession)).andExpect(status().isUnauthorized());
    }
    @Test void activeReferencesPreventDeactivation() throws Exception {
        write("PUT","/api/organizations/companies/"+companyId,Map.of("name","演示公司","enabled",false),"admin").andExpect(status().isConflict());
        write("PUT","/api/organizations/departments/"+departmentId,Map.of("companyId",companyId,"name","演示部门","enabled",false),"hr").andExpect(status().isConflict());
        jdbc.update("INSERT INTO employee_workplace(user_id,workplace_id) VALUES(?,?)",employeeId,placeId);
        write("PUT","/api/workplaces/"+placeId,Map.of("companyId",companyId,"name","测试场所","latitude",31.2,"longitude",121.5,"coordinateSystem","WGS84","radiusMeters",100,"enabled",false),"hr").andExpect(status().isConflict());
    }
    @Test void validationAndCsrfAreEnforced() throws Exception {
        mvc.perform(post("/api/employees").with(user("admin")).contentType("application/json").content(json.writeValueAsString(employee("csrf","EMPLOYEE",companyId)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/employees").with(user("admin")).param("size","101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/workplaces").with(user("admin")).param("page","0")).andExpect(status().isBadRequest());
        write("POST","/api/workplaces",Map.of("companyId",companyId,"name","错误位置","latitude",91,"longitude",120,"coordinateSystem","WGS84","radiusMeters",100,"enabled",true),"hr").andExpect(status().isBadRequest());
        write("POST","/api/employees",employee("duplicate","EMPLOYEE",companyId),"admin").andExpect(status().isOk());
        write("POST","/api/employees",employee("duplicate","EMPLOYEE",companyId),"admin").andExpect(status().isConflict());
    }
    @Test void fourCharacterPasswordCanBeAssignedAndUsedButThreeIsRejected() throws Exception {
        var body=employee("fourpass","EMPLOYEE",companyId);
        body.put("password","123");
        write("POST","/api/employees",body,"admin").andExpect(status().isBadRequest());
        body.put("password","1234");
        write("POST","/api/employees",body,"admin").andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").with(csrf()).param("username","fourpass").param("password","1234"))
                .andExpect(status().isOk());
    }
    @Test void legacyDepartmentCanBeRetiredAfterEmployeesHaveMoved() throws Exception {
        jdbc.update("INSERT INTO sys_department(name) VALUES('旧版未分配部门')");
        Long legacy=jdbc.queryForObject("SELECT id FROM sys_department WHERE name='旧版未分配部门'",Long.class);
        Map<String,Object> body=new HashMap<>(); body.put("name","旧版未分配部门"); body.put("companyId",null); body.put("enabled",false);
        write("PUT","/api/organizations/departments/"+legacy,body,"admin").andExpect(status().isOk());
        write("PUT","/api/organizations/departments/"+legacy,body,"hr").andExpect(status().isForbidden());
    }
    @Test void sharedServicesApplyAuthoritativeScopeAndIgnoreDisabledBindings() {
        var actor=new UsernamePasswordAuthenticationToken("employee","unused");
        jdbc.update("INSERT INTO employee_workplace(user_id,workplace_id) VALUES(?,?)",employeeId,placeId);
        assertThat(workplaces.activeForEmployee(actor,employeeId)).hasSize(1);
        jdbc.update("UPDATE workplace SET enabled=FALSE WHERE id=?",placeId);
        assertThat(workplaces.activeForEmployee(actor,employeeId)).isEmpty();
        Long hrId=jdbc.queryForObject("SELECT id FROM sys_user WHERE username='hr'",Long.class);
        assertThatThrownBy(()->employees.get(actor,hrId)).isInstanceOf(cn.lianzhi.attendance.common.BusinessException.class);
    }
}
