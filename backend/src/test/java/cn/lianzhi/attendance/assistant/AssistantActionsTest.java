package cn.lianzhi.attendance.assistant;

import cn.lianzhi.attendance.approval.*;
import cn.lianzhi.attendance.common.BusinessException;
import cn.lianzhi.attendance.identity.*;
import cn.lianzhi.attendance.organization.DataScope;
import cn.lianzhi.attendance.knowledge.retrieval.KnowledgeIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Fresh isolated database, committed synthetic fixtures: confirmation uses its own real transaction. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:c-day5-actions;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test") @AutoConfigureMockMvc
class AssistantActionsTest {
    @Autowired AssistantActions actions;@Autowired ApprovalService approvals;@Autowired DataScope scope;@Autowired KnowledgeIdentity identity;
    @Autowired PlatformTransactionManager transactions;@Autowired AccountMapper accounts;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;@Autowired ObjectMapper json;
    String username;long employee,company,place;Authentication owner;LocalDate date;MockHttpSession session;
    @BeforeEach void setup(){
        username="c5-"+UUID.randomUUID().toString().substring(0,10);date=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(2);session=new MockHttpSession();
        company=jdbc.queryForObject("SELECT company_id FROM sys_user WHERE username='hr'",Long.class);
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,role,employee_no,company_id) VALUES(?,'synthetic','确认流程合成员工','EMPLOYEE',?,?)",username,username,company);
        employee=jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,username);
        jdbc.update("INSERT INTO workplace(company_id,name,latitude,longitude,coordinate_system,radius_meters) VALUES(?,?,31,121,'WGS84',100)",company,username);
        place=jdbc.queryForObject("SELECT id FROM workplace WHERE name=?",Long.class,username);
        jdbc.update("INSERT INTO employee_workplace(user_id,workplace_id) VALUES(?,?)",employee,place);
        owner=new UsernamePasswordAuthenticationToken(username,"",List.of());
    }
    @AfterEach void cleanup(){
        jdbc.update("DELETE FROM approval_transition WHERE approval_id IN (SELECT id FROM approval_request WHERE applicant_id=?)",employee);
        jdbc.update("DELETE FROM approval_request WHERE applicant_id=?",employee);
        jdbc.update("DELETE FROM employee_workplace WHERE user_id=?",employee);jdbc.update("DELETE FROM sys_user WHERE id=?",employee);jdbc.update("DELETE FROM workplace WHERE id=?",place);
    }
    ApprovalRules.Draft draft(){return new ApprovalRules.Draft(ApprovalRules.Type.LEAVE,date.atTime(9,0),date.atTime(18,0),null,null,"合成事由");}
    AssistantActions.Confirmation prepare(){return actions.prepare(owner,session,new AssistantActions.Prepare(UUID.randomUUID().toString(),draft()));}
    AssistantActions.Confirm confirm(AssistantActions.Confirmation p){return new AssistantActions.Confirm(p.confirmationId(),true);}
    long count(){return jdbc.queryForObject("SELECT COUNT(*) FROM approval_request WHERE applicant_id=?",Long.class,employee);}
    @Test void previewDoesNotCreateApplicationOrAudit(){var p=prepare();assertThat(p.preview().stored()).isFalse();assertThat(count()).isZero();}
    @Test void sameIntentAndContentReturnsSameConfirmation(){String key=UUID.randomUUID().toString();var input=new AssistantActions.Prepare(key,draft());assertThat(actions.prepare(owner,session,input)).isEqualTo(actions.prepare(owner,session,input));assertThat(count()).isZero();}
    @Test void changedContentCannotReuseAnIntent(){String key=UUID.randomUUID().toString();actions.prepare(owner,session,new AssistantActions.Prepare(key,draft()));var d=draft();var changed=new ApprovalRules.Draft(d.type(),d.startAt(),d.endAt(),null,null,"不同事由");assertThatThrownBy(()->actions.prepare(owner,session,new AssistantActions.Prepare(key,changed))).isInstanceOf(BusinessException.class);}
    @Test void explicitConfirmationIsRequired(){var p=prepare();assertThatThrownBy(()->actions.confirm(owner,session,new AssistantActions.Confirm(p.confirmationId(),false))).isInstanceOf(BusinessException.class);assertThat(count()).isZero();}
    @Test void repeatedConfirmationReturnsOneRealApplicationAndOneAudit(){var p=prepare();var a=actions.confirm(owner,session,confirm(p));var b=actions.confirm(owner,session,confirm(p));assertThat(a.request().id()).isEqualTo(b.request().id());assertThat(count()).isEqualTo(1);assertThat(approvals.detail(owner,a.request().id()).history()).hasSize(1);}
    @Test void forgedIdentityAndDraftInConfirmAreIgnored() throws Exception {
        var input=new HashMap<String,Object>();input.put("intentKey",UUID.randomUUID().toString());input.put("draft",draft());input.put("userId",1);input.put("companyId",999);
        var response=mvc.perform(post("/api/assistant/approval-drafts").session(session).with(user(username)).with(csrf()).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isOk()).andReturn();
        String id=json.readTree(response.getResponse().getContentAsString()).path("data").path("confirmationId").asText();
        mvc.perform(post("/api/assistant/approval-drafts/confirm").session(session).with(user(username)).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("confirmationId",id,"confirmed",true,"draft",Map.of("reason","FORGED"),"userId",1))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.request.applicantId").value(employee)).andExpect(jsonPath("$.data.request.draft.reason").value("合成事由"));
    }
    @Test void anotherSessionCannotConfirm(){var p=prepare();assertThatThrownBy(()->actions.confirm(owner,new MockHttpSession(),confirm(p))).isInstanceOf(BusinessException.class);assertThat(count()).isZero();}
    @Test void anotherIdentityCannotUseSameSessionDraft(){var p=prepare();var other=new UsernamePasswordAuthenticationToken("hr","",List.of());assertThatThrownBy(()->actions.confirm(other,session,confirm(p))).isInstanceOf(BusinessException.class);assertThat(count()).isZero();}
    @Test void cancelledDraftCannotWriteAndSubmittedApplicationCannotBeCancelledAsDraft(){var p=prepare();actions.cancel(owner,session,p.confirmationId());assertThatThrownBy(()->actions.confirm(owner,session,confirm(p))).isInstanceOf(BusinessException.class);var next=prepare();actions.confirm(owner,session,confirm(next));assertThatThrownBy(()->actions.cancel(owner,session,next.confirmationId())).isInstanceOf(BusinessException.class);assertThat(count()).isEqualTo(1);}
    @Test void expiredConfirmationCannotWrite(){var clock=new MutableClock();var service=new AssistantActions(approvals,scope,identity,transactions,clock);var p=service.prepare(owner,session,new AssistantActions.Prepare(UUID.randomUUID().toString(),draft()));clock.now=clock.now.plusSeconds(600);assertThatThrownBy(()->service.confirm(owner,session,confirm(p))).isInstanceOf(BusinessException.class);assertThat(count()).isZero();}
    @Test void changedAuthVersionCannotWrite(){var p=prepare();jdbc.update("UPDATE sys_user SET auth_version=auth_version+1 WHERE id=?",employee);assertThatThrownBy(()->actions.confirm(owner,session,confirm(p))).isInstanceOf(BusinessException.class);assertThat(count()).isZero();}
    @Test void changedCompanyCannotWrite(){var p=prepare();jdbc.update("UPDATE sys_user SET company_id=NULL WHERE id=?",employee);assertThatThrownBy(()->actions.confirm(owner,session,confirm(p))).isInstanceOf(BusinessException.class);assertThat(count()).isZero();}
    @Test void missingPunchRechecksCurrentWorkplaceBinding(){var d=new ApprovalRules.Draft(ApprovalRules.Type.MISSING_PUNCH,date.minusDays(3).atTime(9,0),null,ApprovalRules.PunchType.IN,place,"补卡");var p=actions.prepare(owner,session,new AssistantActions.Prepare(UUID.randomUUID().toString(),d));jdbc.update("DELETE FROM employee_workplace WHERE user_id=?",employee);assertThatThrownBy(()->actions.confirm(owner,session,confirm(p))).isInstanceOf(BusinessException.class);assertThat(count()).isZero();}
    @Test void concurrentConfirmationsCommitOnlyOnce() throws Exception {
        var p=prepare();var executor=Executors.newFixedThreadPool(2);try{var a=executor.submit(()->actions.confirm(owner,session,confirm(p)));var b=executor.submit(()->actions.confirm(owner,session,confirm(p)));assertThat(a.get(10,TimeUnit.SECONDS).request().id()).isEqualTo(b.get(10,TimeUnit.SECONDS).request().id());assertThat(count()).isEqualTo(1);}finally{executor.shutdownNow();}
    }
    @Test void failedBusinessValidationDoesNotPretendSubmissionSucceeded(){approvals.create(owner,new ApprovalService.Submission(UUID.randomUUID().toString(),draft()));var p=prepare();assertThatThrownBy(()->actions.confirm(owner,session,confirm(p))).isInstanceOf(BusinessException.class);actions.cancel(owner,session,p.confirmationId());assertThat(count()).isEqualTo(1);}
    @Test void identityRaceAfterAInsertionRollsBackApplicationAndAudit(){
        UserAccount a=accounts.findByUsername(username);var changed=new UserAccount(a.id(),a.username(),a.passwordHash(),a.displayName(),a.role(),a.employeeNo(),a.departmentId(),a.departmentName(),a.enabled(),a.createdAt(),a.companyId(),a.authVersion()+1);
        var fakeScope=mock(DataScope.class);when(fakeScope.actor(owner)).thenReturn(a,a,a,changed);
        var boundary=new AssistantActions(approvals,fakeScope,identity,transactions);var p=boundary.prepare(owner,session,new AssistantActions.Prepare(UUID.randomUUID().toString(),draft()));
        assertThatThrownBy(()->boundary.confirm(owner,session,confirm(p))).isInstanceOf(BusinessException.class);assertThat(count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_transition WHERE actor_id=?",Long.class,employee)).isZero();
    }
    @Test void prepareAndConfirmAndCancelRequireCsrf() throws Exception {for(String path:List.of("/approval-drafts","/approval-drafts/confirm","/approval-drafts/x/cancel"))mvc.perform(post("/api/assistant"+path).session(session).with(user(username))).andExpect(status().isForbidden());}
    @Test void sessionBudgetIsBounded(){for(int i=0;i<10;i++)prepare();assertThatThrownBy(this::prepare).isInstanceOf(BusinessException.class);assertThat(count()).isZero();}
    static class MutableClock extends Clock {Instant now=Instant.parse("2026-11-01T08:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}}
}
