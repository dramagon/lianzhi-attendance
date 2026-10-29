package cn.lianzhi.attendance;

import cn.lianzhi.attendance.approval.*;
import cn.lianzhi.attendance.attendance.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** No outer test transaction: observes actual commits, rollback and concurrent connections. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class ApprovalWorkflowTest {
    @Autowired ApprovalService approvals; @Autowired AttendanceDays days; @Autowired AttendanceSchedules schedules;
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired ObjectMapper json;
    long employee,company,place,admin;String username;LocalDate day;
    Authentication owner,manager=new UsernamePasswordAuthenticationToken("admin","",List.of());
    @BeforeEach void setup(){
        username="ap-"+UUID.randomUUID().toString().substring(0,12);day=LocalDate.now(AttendanceRules.ZONE).plusDays(2);
        company=jdbc.queryForObject("SELECT company_id FROM sys_user WHERE username='hr'",Long.class);
        admin=jdbc.queryForObject("SELECT id FROM sys_user WHERE username='admin'",Long.class);
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,role,employee_no,company_id) VALUES(?,'test-hash','审批员工','EMPLOYEE',?,?)",username,username,company);
        employee=jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,username);
        jdbc.update("INSERT INTO workplace(company_id,name,latitude,longitude,coordinate_system,radius_meters) VALUES(?,?,31,121,'WGS84',100)",company,username);
        place=jdbc.queryForObject("SELECT id FROM workplace WHERE name=?",Long.class,username);
        jdbc.update("INSERT INTO employee_workplace(user_id,workplace_id) VALUES(?,?)",employee,place);
        owner=new UsernamePasswordAuthenticationToken(username,"",List.of());
        schedules.save(manager,new AttendanceSchedules.Schedule(employee,day,place,new AttendanceRules.Rule(LocalTime.of(9,0),LocalTime.of(18,0),LocalTime.NOON,LocalTime.of(13,0),5,EnumSet.allOf(DayOfWeek.class)),false));
    }
    @AfterEach void cleanup(){
        jdbc.update("DELETE FROM approval_transition WHERE approval_id IN (SELECT id FROM approval_request WHERE applicant_id=?)",employee);
        for(String table:List.of("approval_request","attendance_adjustment","attendance_event","attendance_schedule","employee_workplace"))jdbc.update("DELETE FROM "+table+" WHERE "+(table.equals("approval_request")?"applicant_id":"user_id")+"=?",employee);
        jdbc.update("DELETE FROM sys_user WHERE id=?",employee);jdbc.update("DELETE FROM workplace WHERE id=?",place);
    }
    ApprovalRules.Draft leave(){return new ApprovalRules.Draft(ApprovalRules.Type.LEAVE,day.atTime(9,0),day.atTime(18,0),null,null,"看病请假");}
    ApprovalStore.Entry create(ApprovalRules.Draft draft){return approvals.create(owner,new ApprovalService.Submission(UUID.randomUUID().toString(),draft));}
    ApprovalService.Decision decision(ApprovalRules.Action action){return new ApprovalService.Decision(action,0,action==ApprovalRules.Action.REJECT?"请补充证明":"");}
    long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+(table.equals("approval_transition")?"approval_id IN (SELECT id FROM approval_request WHERE applicant_id=?)":"user_id=?"),Long.class,employee);}
    @Test void realHttpCreateUsesOwnIdentityAndRequiresCsrf() throws Exception {
        var data=new HashMap<String,Object>();data.put("requestKey",UUID.randomUUID().toString());data.put("draft",leave());data.put("applicantId",admin);
        mvc.perform(post("/api/approvals").with(user(username)).contentType("application/json").content(json.writeValueAsString(data))).andExpect(status().isForbidden());
        var response=mvc.perform(post("/api/approvals").with(user(username)).with(csrf()).contentType("application/json").content(json.writeValueAsString(data))).andExpect(status().isOk()).andExpect(jsonPath("$.data.request.applicantId").value(employee)).andReturn();
        long id=json.readTree(response.getResponse().getContentAsString()).path("data").path("request").path("id").asLong();
        mvc.perform(get("/api/approvals/"+id).with(user("employee"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/approvals/me").with(user(username))).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(post("/api/approvals/"+id+"/actions").with(user(username)).with(csrf()).contentType("application/json").content(json.writeValueAsString(decision(ApprovalRules.Action.APPROVE)))).andExpect(status().isForbidden());
        mvc.perform(post("/api/approvals/"+id+"/actions").with(user("admin").roles("ADMIN")).contentType("application/json").content(json.writeValueAsString(decision(ApprovalRules.Action.APPROVE)))).andExpect(status().isForbidden());
    }
    @Test void requestKeyReplayAndOverlapsAreEnforced(){
        var input=new ApprovalService.Submission(UUID.randomUUID().toString(),leave());var first=approvals.create(owner,input);
        assertThat(approvals.create(owner,input).request().id()).isEqualTo(first.request().id());assertThat(count("approval_transition")).isEqualTo(1);
        assertThatThrownBy(()->approvals.create(owner,new ApprovalService.Submission(input.requestKey(),new ApprovalRules.Draft(ApprovalRules.Type.LEAVE,day.atTime(10,0),day.atTime(11,0),null,null,"不同内容")))).hasMessageContaining("不同内容");
        assertThatThrownBy(()->create(leave())).hasMessageContaining("重叠");
    }
    @Test void approvalCommitsAuditAndAdjustmentTogetherAndExactRetryIsIdempotent(){
        var r=create(leave());var result=approvals.decide(manager,r.request().id(),decision(ApprovalRules.Action.APPROVE));
        assertThat(result.entry().request().state()).isEqualTo(ApprovalRules.State.APPROVED);assertThat(result.history()).hasSize(2);
        assertThat(count("attendance_adjustment")).isEqualTo(1);assertThat(count("attendance_event")).isZero();
        var daily=days.list(owner,employee,day,day).get(0);assertThat(daily.attendance().status()).isEqualTo("ON_LEAVE");assertThat(daily.leaveMinutes()).isEqualTo(480);assertThat(daily.approvalIds()).containsExactly(r.request().id());
        approvals.decide(manager,r.request().id(),decision(ApprovalRules.Action.APPROVE));assertThat(count("approval_transition")).isEqualTo(2);assertThat(count("attendance_adjustment")).isEqualTo(1);
        assertThatThrownBy(()->approvals.decide(owner,r.request().id(),new ApprovalService.Decision(ApprovalRules.Action.WITHDRAW,1,""))).hasMessageContaining("仅待审批");
    }
    @Test void invalidScheduleRollsBackStateAuditAndAdjustment(){
        var invalid=new ApprovalRules.Draft(ApprovalRules.Type.OVERTIME,day.atTime(10,0),day.atTime(11,0),null,null,"正常班次重叠");var r=create(invalid);
        assertThatThrownBy(()->approvals.decide(manager,r.request().id(),decision(ApprovalRules.Action.APPROVE))).hasMessageContaining("正常班次重叠");
        assertThat(approvals.detail(owner,r.request().id()).entry().request().state()).isEqualTo(ApprovalRules.State.PENDING);assertThat(count("approval_transition")).isEqualTo(1);assertThat(count("attendance_adjustment")).isZero();
    }
    @Test void failureAfterAdjustmentInsertRollsBackEveryTable(){
        // Already punched IN; OUT correction earlier than IN passes initial checks but fails B projection after insert.
        var past=LocalDate.now(AttendanceRules.ZONE).minusDays(2);
        jdbc.update("UPDATE attendance_schedule SET business_date=? WHERE user_id=?",past,employee);
        jdbc.update("INSERT INTO attendance_event(user_id,workplace_id,business_date,event_type,occurred_at,request_id,qr_nonce,distance_meters) VALUES(?,?,?,'IN',?,?,?,0)",employee,place,past,java.sql.Timestamp.from(past.atTime(10,0).atZone(AttendanceRules.ZONE).toInstant()),UUID.randomUUID().toString(),UUID.randomUUID().toString());
        var r=create(new ApprovalRules.Draft(ApprovalRules.Type.MISSING_PUNCH,past.atTime(9,0),null,ApprovalRules.PunchType.OUT,place,"补签退"));
        assertThatThrownBy(()->approvals.decide(manager,r.request().id(),decision(ApprovalRules.Action.APPROVE))).hasMessageContaining("签退须晚于签到");
        assertThat(approvals.detail(owner,r.request().id()).entry().request().version()).isZero();assertThat(count("attendance_adjustment")).isZero();assertThat(count("approval_transition")).isEqualTo(1);assertThat(count("attendance_event")).isEqualTo(1);
    }
    @Test void rejectWithdrawAndStaleVersionsHaveNoAdjustment(){
        var r=create(leave());assertThatThrownBy(()->approvals.decide(manager,r.request().id(),new ApprovalService.Decision(ApprovalRules.Action.REJECT,0,""))).hasMessageContaining("原因");
        approvals.decide(manager,r.request().id(),decision(ApprovalRules.Action.REJECT));assertThat(count("attendance_adjustment")).isZero();
        var next=create(leave());approvals.decide(owner,next.request().id(),decision(ApprovalRules.Action.WITHDRAW));
        assertThatThrownBy(()->approvals.decide(manager,next.request().id(),decision(ApprovalRules.Action.APPROVE))).hasMessageContaining("已被更新");
    }
    @Test void successfulMissingPunchKeepsOriginalEventsSeparate(){
        var past=LocalDate.now(AttendanceRules.ZONE).minusDays(2);jdbc.update("UPDATE attendance_schedule SET business_date=? WHERE user_id=?",past,employee);
        var r=create(new ApprovalRules.Draft(ApprovalRules.Type.MISSING_PUNCH,past.atTime(9,0),null,ApprovalRules.PunchType.IN,place,"忘记签到"));
        approvals.decide(manager,r.request().id(),decision(ApprovalRules.Action.APPROVE));
        var daily=days.list(owner,employee,past,past).get(0);assertThat(daily.checkIn()).isEqualTo(past.atTime(9,0));assertThat(daily.approvalIds()).containsExactly(r.request().id());assertThat(count("attendance_event")).isZero();
    }
    @Test void overtimeUsesActualAttendanceRatherThanRequestedDuration(){
        var past=LocalDate.now(AttendanceRules.ZONE).minusDays(2);jdbc.update("UPDATE attendance_schedule SET business_date=? WHERE user_id=?",past,employee);
        for(var type:List.of("IN","OUT"))jdbc.update("INSERT INTO attendance_event(user_id,workplace_id,business_date,event_type,occurred_at,request_id,qr_nonce,distance_meters) VALUES(?,?,?,?,?,?,?,0)",employee,place,past,type,java.sql.Timestamp.from(past.atTime(type.equals("IN")?9:19,0).atZone(AttendanceRules.ZONE).toInstant()),UUID.randomUUID().toString(),UUID.randomUUID().toString());
        var r=create(new ApprovalRules.Draft(ApprovalRules.Type.OVERTIME,past.atTime(18,0),past.atTime(20,0),null,null,"项目加班"));approvals.decide(manager,r.request().id(),decision(ApprovalRules.Action.APPROVE));
        var daily=days.list(owner,employee,past,past).get(0);assertThat(daily.overtimeMinutes()).isEqualTo(60);assertThat(daily.attendance().workMinutes()).isEqualTo(480);assertThat(count("attendance_event")).isEqualTo(2);
    }
    @Test void approvalRechecksRevokedWorkplaceBinding(){
        var past=LocalDate.now(AttendanceRules.ZONE).minusDays(2);jdbc.update("UPDATE attendance_schedule SET business_date=? WHERE user_id=?",past,employee);
        var r=create(new ApprovalRules.Draft(ApprovalRules.Type.MISSING_PUNCH,past.atTime(9,0),null,ApprovalRules.PunchType.IN,place,"忘记签到"));
        jdbc.update("DELETE FROM employee_workplace WHERE user_id=?",employee);
        assertThatThrownBy(()->approvals.decide(manager,r.request().id(),decision(ApprovalRules.Action.APPROVE))).hasMessageContaining("绑定已失效");assertThat(count("approval_transition")).isEqualTo(1);assertThat(count("attendance_adjustment")).isZero();
    }
    @Test void sameSecondOriginalPunchesKeepOrderAfterDatabaseRoundTrip(){
        var past=LocalDate.now(AttendanceRules.ZONE).minusDays(2);jdbc.update("UPDATE attendance_schedule SET business_date=? WHERE user_id=?",past,employee);
        var in=past.atTime(9,0).plusNanos(100_000_000);var out=in.plusNanos(200_000_000);
        for(var type:List.of("IN","OUT"))jdbc.update("INSERT INTO attendance_event(user_id,workplace_id,business_date,event_type,occurred_at,request_id,qr_nonce,distance_meters) VALUES(?,?,?,?,?,?,?,0)",employee,place,past,type,java.sql.Timestamp.from((type.equals("IN")?in:out).atZone(AttendanceRules.ZONE).toInstant()),UUID.randomUUID().toString(),UUID.randomUUID().toString());
        var daily=days.list(owner,employee,past,past).get(0);assertThat(daily.checkIn()).isEqualTo(in);assertThat(daily.checkOut()).isEqualTo(out);assertThat(daily.attendance().workMinutes()).isZero();
    }
    @Test void crossCompanyManagerCannotReadOrActAndTransferRequiresResubmission(){
        var r=create(leave());
        jdbc.update("UPDATE sys_user SET company_id=NULL WHERE id=?",employee);
        var hr=new UsernamePasswordAuthenticationToken("hr","",List.of());
        assertThatThrownBy(()->approvals.detail(hr,r.request().id())).isInstanceOf(RuntimeException.class);
        assertThat(approvals.list(hr,true,"PENDING",1,100).items()).noneMatch(e->e.request().id()==r.request().id());
        assertThatThrownBy(()->approvals.decide(manager,r.request().id(),decision(ApprovalRules.Action.APPROVE))).hasMessageContaining("归属已改变");
        approvals.decide(owner,r.request().id(),decision(ApprovalRules.Action.WITHDRAW));
    }
    private List<Object> race(Callable<Object> one,Callable<Object> two) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{var tasks=List.of(one,two).stream().map(task->pool.submit(()->{start.await();try{return task.call();}catch(Exception e){return e;}})).toList();start.countDown();return List.of(tasks.get(0).get(20,TimeUnit.SECONDS),tasks.get(1).get(20,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
    }
    @Test void concurrentSameKeyCreatesExactlyOneRequest() throws Exception {
        var input=new ApprovalService.Submission(UUID.randomUUID().toString(),leave());var result=race(()->approvals.create(owner,input),()->approvals.create(owner,input));
        assertThat(result).allMatch(o->o instanceof ApprovalStore.Entry);assertThat(((ApprovalStore.Entry)result.get(0)).request().id()).isEqualTo(((ApprovalStore.Entry)result.get(1)).request().id());assertThat(count("approval_transition")).isEqualTo(1);
    }
    @Test void concurrentApprovalAndWithdrawalHaveOneWinner() throws Exception {
        var r=create(leave());var result=race(()->approvals.decide(manager,r.request().id(),decision(ApprovalRules.Action.APPROVE)),()->approvals.decide(owner,r.request().id(),decision(ApprovalRules.Action.WITHDRAW)));
        assertThat(result.stream().filter(o->o instanceof ApprovalService.Detail).count()).isEqualTo(1);assertThat(count("approval_transition")).isEqualTo(2);
        var state=approvals.detail(owner,r.request().id()).entry().request().state();assertThat(count("attendance_adjustment")).isEqualTo(state==ApprovalRules.State.APPROVED?1:0);
    }
    @Test void concurrentIdenticalApprovalIsIdempotent() throws Exception {
        var r=create(leave());var result=race(()->approvals.decide(manager,r.request().id(),decision(ApprovalRules.Action.APPROVE)),()->approvals.decide(manager,r.request().id(),decision(ApprovalRules.Action.APPROVE)));
        assertThat(result).allMatch(o->o instanceof ApprovalService.Detail);assertThat(count("approval_transition")).isEqualTo(2);assertThat(count("attendance_adjustment")).isEqualTo(1);
    }
}
