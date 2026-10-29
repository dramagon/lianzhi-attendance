package cn.lianzhi.attendance.assistant;

import cn.lianzhi.attendance.approval.*;
import cn.lianzhi.attendance.attendance.*;
import cn.lianzhi.attendance.identity.*;
import cn.lianzhi.attendance.knowledge.retrieval.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:c-day4;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","LIANZHI_AI_BASE_URL=","LIANZHI_AI_MODEL=","LIANZHI_EMBEDDING_BASE_URL=","LIANZHI_EMBEDDING_MODEL="})
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
class AssistantIntegrationTest {
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired AttendanceSchedules schedules;
    @Autowired ApprovalService approvals;@Autowired KnowledgeIdentity identity;@Autowired AccountMapper accounts;@Autowired ApplicationContext context;
    String username;long employee,company,place;LocalDate date;Authentication owner,admin=new UsernamePasswordAuthenticationToken("admin","",List.of());
    @BeforeEach void setup(){
        username="c-"+UUID.randomUUID().toString().substring(0,12);date=LocalDate.now(AttendanceRules.ZONE).plusDays(1);
        company=jdbc.queryForObject("SELECT company_id FROM sys_user WHERE username='hr'",Long.class);
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,role,employee_no,company_id) VALUES(?,'synthetic','合成员工','EMPLOYEE',?,?)",username,username,company);
        employee=jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,username);
        jdbc.update("INSERT INTO workplace(company_id,name,latitude,longitude,coordinate_system,radius_meters) VALUES(?,?,31,121,'WGS84',100)",company,username);
        place=jdbc.queryForObject("SELECT id FROM workplace WHERE name=?",Long.class,username);
        jdbc.update("INSERT INTO employee_workplace(user_id,workplace_id) VALUES(?,?)",employee,place);
        owner=new UsernamePasswordAuthenticationToken(username,"",List.of());
        schedules.save(admin,new AttendanceSchedules.Schedule(employee,date,place,new AttendanceRules.Rule(LocalTime.of(9,0),LocalTime.of(18,0),LocalTime.NOON,LocalTime.of(13,0),5,EnumSet.allOf(DayOfWeek.class)),false));
    }
    String dayUrl(String prefix){return prefix+"?from="+date+"&to="+date;}
    @Test void identityComesFromAAccountAndCurrentCompany(){
        var first=identity.current(owner);assertThat(first.userId()).isEqualTo(employee);assertThat(first.companyId()).isEqualTo(company);assertThat(first.role()).isEqualTo(KnowledgeActor.Role.EMPLOYEE);
        jdbc.update("INSERT INTO org_company(name) VALUES(?)",username+"-new");long next=jdbc.queryForObject("SELECT id FROM org_company WHERE name=?",Long.class,username+"-new");
        jdbc.update("UPDATE sys_user SET company_id=? WHERE id=?",next,employee);
        assertThat(identity.current(owner).companyId()).isEqualTo(next);
    }
    @Test void missingCompanyAndDisabledUserOrCompanyCannotUseBusinessQueries() throws Exception {
        jdbc.update("UPDATE sys_user SET company_id=NULL WHERE id=?",employee);
        mvc.perform(get(dayUrl("/api/assistant/attendance")).with(user(username))).andExpect(status().isForbidden());
        jdbc.update("UPDATE sys_user SET company_id=?,enabled=FALSE WHERE id=?",company,employee);
        assertThatThrownBy(()->identity.current(owner)).isInstanceOf(cn.lianzhi.attendance.common.BusinessException.class);
        jdbc.update("UPDATE sys_user SET enabled=TRUE WHERE id=?",employee);jdbc.update("UPDATE org_company SET enabled=FALSE WHERE id=?",company);
        mvc.perform(get(dayUrl("/api/assistant/attendance")).with(user(username))).andExpect(status().isForbidden());
    }
    @Test void bAndAssistantReturnExactlyTheSameDailyResults() throws Exception {
        String direct=mvc.perform(get(dayUrl("/api/attendance/me/days")).with(user(username))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String c=mvc.perform(get(dayUrl("/api/assistant/attendance")).with(user(username))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(c).path("data")).isEqualTo(json.readTree(direct).path("data"));
        assertThat(json.readTree(c).path("data").size()).isEqualTo(1);
    }
    @Test void employeeCannotForgeTargetUserAndHrCannotCrossCompanies() throws Exception {
        long other=jdbc.queryForObject("SELECT id FROM sys_user WHERE username='admin'",Long.class);
        mvc.perform(get(dayUrl("/api/assistant/attendance")+"&userId="+other).with(user(username))).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO org_company(name) VALUES(?)",username+"-other");long foreign=jdbc.queryForObject("SELECT id FROM org_company WHERE name=?",Long.class,username+"-other");
        jdbc.update("UPDATE sys_user SET company_id=? WHERE id=?",foreign,employee);
        mvc.perform(get(dayUrl("/api/assistant/attendance")+"&userId="+employee).with(user("hr").roles("HR"))).andExpect(status().isForbidden());
    }
    @Test void hrCanReadOwnCompanyEmployeeWithoutRecalculating() throws Exception {
        mvc.perform(get(dayUrl("/api/assistant/attendance")+"&userId="+employee).with(user("hr").roles("HR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].userId").value(employee));
    }
    @Test void aAndAssistantReturnSameApplicationAndAudit() throws Exception {
        var draft=new ApprovalRules.Draft(ApprovalRules.Type.LEAVE,date.atTime(9,0),date.atTime(18,0),null,null,"合成测试申请");
        long id=approvals.create(owner,new ApprovalService.Submission(UUID.randomUUID().toString(),draft)).request().id();
        String a=mvc.perform(get("/api/approvals/"+id).with(user(username))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String c=mvc.perform(get("/api/assistant/approvals/"+id).with(user(username))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(a).path("data")).isEqualTo(json.readTree(c).path("data"));
        mvc.perform(get("/api/assistant/approvals").with(user(username))).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].request.id").value(id));
        mvc.perform(get("/api/assistant/approvals/"+id).with(user("employee"))).andExpect(status().isForbidden());
    }
    @Test void statusIsHonestAndAskDoesNotBypassCsrfOrMissingStore() throws Exception {
        mvc.perform(get("/api/assistant/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/assistant/status").with(user(username))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.knowledgeAvailable").value(false)).andExpect(jsonPath("$.data.businessQueriesAvailable").value(true))
                .andExpect(jsonPath("$.data.writeAvailable").value(false)).andExpect(jsonPath("$.data.modelConfiguration").value("NOT_CONFIGURED"));
        mvc.perform(post("/api/assistant/ask").with(user(username))).andExpect(status().isForbidden());
        mvc.perform(post("/api/assistant/ask").with(user(username)).with(csrf())).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.data").isEmpty());
        assertThat(context.getBeansOfType(KnowledgeWorkspace.class)).isEmpty();assertThat(context.getBeansOfType(CitedAnswerService.class)).isEmpty();
    }
    @Test void invalidRangeAndPaginationReturnErrorsNotFakeEmptyData() throws Exception {
        mvc.perform(get("/api/assistant/attendance?from="+date+"&to="+date.plusDays(94)).with(user(username))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/assistant/approvals?size=101").with(user(username))).andExpect(status().isBadRequest());
    }
    @Test void realSessionIsRevokedAfterAccountChanges() throws Exception {
        var principal=new SessionUser(accounts.findByUsername(username));jdbc.update("UPDATE sys_user SET auth_version=auth_version+1 WHERE id=?",employee);
        mvc.perform(get("/api/assistant/status").with(user(principal))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
    }
}
