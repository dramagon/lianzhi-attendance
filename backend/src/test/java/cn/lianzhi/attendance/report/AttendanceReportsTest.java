package cn.lianzhi.attendance.report;

import cn.lianzhi.attendance.attendance.*;
import cn.lianzhi.attendance.common.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

@SpringBootTest(properties="spring.datasource.url=${LIANZHI_REPORT_TEST_URL:jdbc:h2:mem:c-reports;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}")
@ActiveProfiles("test") @AutoConfigureMockMvc @Transactional
class AttendanceReportsTest {
    @Autowired AttendanceReports reports;@Autowired AttendanceDays days;@Autowired AttendanceSchedules schedules;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
    Authentication hr=new UsernamePasswordAuthenticationToken("hr","",List.of()),admin=new UsernamePasswordAuthenticationToken("admin","",List.of());
    long user,company,department,place;String username;LocalDate date=LocalDate.now(AttendanceRules.ZONE).minusDays(10);
    @BeforeEach void setup(){
        username="rp-"+UUID.randomUUID().toString().substring(0,10);company=jdbc.queryForObject("SELECT company_id FROM sys_user WHERE username='hr'",Long.class);
        jdbc.update("INSERT INTO sys_department(company_id,name) VALUES(?,?)",company,username);department=jdbc.queryForObject("SELECT id FROM sys_department WHERE name=?",Long.class,username);
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,role,employee_no,company_id,department_id) VALUES(?,'synthetic',?,'EMPLOYEE',?,?,?)",username,"=HYPERLINK(\"synthetic\")",username,company,department);
        user=jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,username);
        jdbc.update("INSERT INTO workplace(company_id,name,latitude,longitude,coordinate_system,radius_meters) VALUES(?,?,31,121,'WGS84',100)",company,username);place=jdbc.queryForObject("SELECT id FROM workplace WHERE name=?",Long.class,username);
        shift(date,false);event(date,"IN",9);event(date,"OUT",18);
    }
    void shift(LocalDate d,boolean rest){var rule=new AttendanceRules.Rule(LocalTime.of(9,0),LocalTime.of(18,0),LocalTime.NOON,LocalTime.of(13,0),5,EnumSet.allOf(DayOfWeek.class));jdbc.update("INSERT INTO attendance_schedule(user_id,business_date,workplace_id,rule_json,holiday,created_by) VALUES(?,?,?,?,?,?)",user,d,place,schedules.encode(rule),rest,user);}
    void event(LocalDate d,String type,int hour){jdbc.update("INSERT INTO attendance_event(user_id,workplace_id,business_date,event_type,occurred_at,request_id,qr_nonce,distance_meters) VALUES(?,?,?,?,?,?,?,0)",user,place,d,type,java.sql.Timestamp.from(d.atTime(hour,0).atZone(AttendanceRules.ZONE).toInstant()),UUID.randomUUID().toString(),UUID.randomUUID().toString());}
    AttendanceReports.Filter filter(LocalDate from,LocalDate to){return new AttendanceReports.Filter(from,to,null,department,null,null);}
    @Test void realBValuesMatchRowsTotalsTrendAndHeat(){
        var result=reports.query(hr,filter(date,date),1,20);var b=days.list(hr,user,date,date).get(0);var row=result.records().items().get(0);
        assertThat(row.status()).isEqualTo(b.attendance().status());assertThat(row.workMinutes()).isEqualTo(b.attendance().workMinutes()).isEqualTo(480);
        assertThat(row.checkIn()).isEqualTo(b.checkIn());assertThat(row.approvalIds()).isEqualTo(b.approvalIds());
        assertThat(result.totals().attendanceRate()).isEqualByComparingTo("100.00");assertThat(result.totals().workMinutes()).isEqualTo(480);
        assertThat(result.trend().get(0).totals()).isEqualTo(result.totals());assertThat(result.workplaces().get(0).workMinutes()).isEqualTo(480);assertThat(result.workplaces().get(0).attendedDays()).isEqualTo(1);
    }
    @Test void noSchedulesMeansNoDenominatorAndNoAbsence(){var r=reports.query(hr,filter(date.minusDays(1),date.minusDays(1)),1,20);assertThat(r.totals().attendanceRate()).isNull();assertThat(r.totals().absentDays()).isZero();assertThat(r.records().total()).isZero();assertThat(r.trend()).hasSize(1);}
    @Test void overnightUsesShiftEndAndBusinessDateFromB(){
        var rule=new AttendanceRules.Rule(LocalTime.of(22,0),LocalTime.of(6,0),null,null,0,EnumSet.allOf(DayOfWeek.class));jdbc.update("UPDATE attendance_schedule SET rule_json=? WHERE user_id=?",schedules.encode(rule),user);jdbc.update("DELETE FROM attendance_event WHERE user_id=?",user);
        for(String type:List.of("IN","OUT")){var at=type.equals("IN")?date.atTime(22,0):date.plusDays(1).atTime(6,0);jdbc.update("INSERT INTO attendance_event(user_id,workplace_id,business_date,event_type,occurred_at,request_id,qr_nonce,distance_meters) VALUES(?,?,?,?,?,?,?,0)",user,place,date,type,java.sql.Timestamp.from(at.atZone(AttendanceRules.ZONE).toInstant()),UUID.randomUUID().toString(),UUID.randomUUID().toString());}
        var r=reports.query(hr,filter(date,date),1,20);assertThat(r.records().items().get(0).finalized()).isTrue();assertThat(r.totals().workMinutes()).isEqualTo(480);assertThat(r.totals().expectedDays()).isEqualTo(1);assertThat(reports.query(hr,filter(date.plusDays(1),date.plusDays(1)),1,20).records().total()).isZero();
    }
    @Test void pendingRestAbsenceAndMissingAreDistinct(){
        shift(date.plusDays(1),false);shift(date.plusDays(2),false);event(date.plusDays(2),"IN",9);shift(date.plusDays(3),true);
        LocalDate future=LocalDate.now(AttendanceRules.ZONE).plusDays(1);shift(future,false);
        var t=reports.query(hr,filter(date,future),1,20).totals();assertThat(t.expectedDays()).isEqualTo(3);assertThat(t.attendedDays()).isEqualTo(1);assertThat(t.absentDays()).isEqualTo(1);assertThat(t.missingPunchDays()).isEqualTo(1);assertThat(t.restDays()).isEqualTo(1);assertThat(t.pendingDays()).isEqualTo(1);assertThat(t.attendanceRate()).isEqualByComparingTo("33.33");
    }
    @Test void fullLeaveExcludedPartialLeaveRetainsBAdjustedMinutes(){
        jdbc.update("INSERT INTO attendance_adjustment(approval_id,user_id,company_id,draft_json,approver_id,approved_at) VALUES(?,?,?,?,?,?)",901,user,company,schedules.encode(new cn.lianzhi.attendance.approval.ApprovalRules.Draft(cn.lianzhi.attendance.approval.ApprovalRules.Type.LEAVE,date.atTime(9,0),date.atTime(18,0),null,null,"合成全天请假")),1,date.atTime(8,0));
        var r=reports.query(hr,filter(date,date),1,20);assertThat(r.totals().leaveDays()).isEqualTo(1);assertThat(r.totals().attendanceRate()).isNull();assertThat(r.totals().leaveMinutes()).isEqualTo(480);assertThat(r.records().items().get(0).approvalIds()).contains(901L);
        jdbc.update("UPDATE attendance_adjustment SET draft_json=? WHERE approval_id=901",schedules.encode(new cn.lianzhi.attendance.approval.ApprovalRules.Draft(cn.lianzhi.attendance.approval.ApprovalRules.Type.LEAVE,date.atTime(9,0),date.atTime(10,0),null,null,"合成部分请假")));
        var partial=reports.query(hr,filter(date,date),1,20);assertThat(partial.totals().expectedDays()).isEqualTo(1);assertThat(partial.totals().leaveMinutes()).isEqualTo(60);assertThat(partial.totals().workMinutes()).isEqualTo(days.list(hr,user,date,date).get(0).attendance().workMinutes());
    }
    @Test void paginationFiltersAndCsvUseWholeAuthorizedRange(){
        shift(date.plusDays(1),false);var f=new AttendanceReports.Filter(date,date.plusDays(1),company,department,user,place);var r=reports.query(hr,f,2,1);
        assertThat(r.records().total()).isEqualTo(2);assertThat(r.records().items()).hasSize(1);assertThat(r.totals().scheduledDays()).isEqualTo(2);
        String csv=reports.export(hr,f);assertThat(csv).startsWith("\uFEFF").contains("\"'=HYPERLINK(\"\"synthetic\"\")\"").contains(date.toString(),date.plusDays(1).toString());assertThat(csv.lines().count()).isEqualTo(3);
    }
    @Test void spreadsheetInjectionAndQuotedCellsAreEscaped(){for(String s:List.of("=1+1","+cmd","-2+3","@SUM(A1)","  =1","\t=1","\r\n@a"))assertThat(AttendanceReports.cell(s)).startsWith("\"'");assertThat(AttendanceReports.cell("a,\"b")).isEqualTo("\"a,\"\"b\"");}
    @Test void employeeCannotReadAggregateOrExport(){var actor=new UsernamePasswordAuthenticationToken(username,"",List.of());assertThatThrownBy(()->reports.query(actor,filter(date,date),1,20)).isInstanceOf(BusinessException.class);assertThatThrownBy(()->reports.export(actor,filter(date,date))).isInstanceOf(BusinessException.class);}
    @Test void foreignCompanyDepartmentEmployeeAndPlaceRejected(){
        jdbc.update("INSERT INTO org_company(name) VALUES(?)",username+"foreign");long foreign=jdbc.queryForObject("SELECT id FROM org_company WHERE name=?",Long.class,username+"foreign");
        jdbc.update("INSERT INTO sys_department(company_id,name) VALUES(?,?)",foreign,username+"foreign");long dep=jdbc.queryForObject("SELECT id FROM sys_department WHERE name=?",Long.class,username+"foreign");
        jdbc.update("INSERT INTO workplace(company_id,name,latitude,longitude,coordinate_system,radius_meters) VALUES(?,?,30,120,'GCJ02',100)",foreign,username+"foreign");long location=jdbc.queryForObject("SELECT id FROM workplace WHERE name=?",Long.class,username+"foreign");
        for(var f:List.of(new AttendanceReports.Filter(date,date,foreign,null,null,null),new AttendanceReports.Filter(date,date,null,dep,null,null),new AttendanceReports.Filter(date,date,null,null,null,location)))assertThatThrownBy(()->reports.export(hr,f)).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE sys_user SET company_id=? WHERE id=?",foreign,user);assertThatThrownBy(()->reports.query(hr,new AttendanceReports.Filter(date,date,null,null,user,null),1,20)).isInstanceOf(BusinessException.class);
    }
    @Test void transferDoesNotExposeForeignHistoricalLocation(){jdbc.update("INSERT INTO org_company(name) VALUES(?)",username+"transfer");long foreign=jdbc.queryForObject("SELECT id FROM org_company WHERE name=?",Long.class,username+"transfer");jdbc.update("UPDATE workplace SET company_id=? WHERE id=?",foreign,place);assertThatThrownBy(()->reports.query(admin,filter(date,date),1,20)).isInstanceOf(BusinessException.class).hasMessageContaining("历史排班场所");}
    @Test void disabledAccountsAndUnmatchedWorkplaceHaveNoRows(){jdbc.update("UPDATE sys_user SET enabled=FALSE WHERE id=?",user);assertThat(reports.query(hr,filter(date,date),1,20).records().total()).isZero();}
    @Test void datePageAndEmployeeBudgetsFailWithoutTruncating(){
        assertThatThrownBy(()->reports.query(hr,filter(date,date.plusDays(94)),1,20)).isInstanceOf(BusinessException.class);assertThatThrownBy(()->reports.query(hr,filter(date,date),0,20)).isInstanceOf(BusinessException.class);
        for(int i=0;i<100;i++)jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,role,employee_no,company_id,department_id) VALUES(?,'synthetic','配额测试','EMPLOYEE',?,?,?)",username+i,username+i,company,department);
        assertThatThrownBy(()->reports.export(hr,filter(date,date))).isInstanceOf(BusinessException.class);
        assertThat(reports.query(hr,new AttendanceReports.Filter(date,date,null,department,user,null),1,20).records().total()).isEqualTo(1);
    }
    @Test void httpAuthorizationAndCsvHeaders() throws Exception {
        String query="?from="+date+"&to="+date+"&departmentId="+department;
        mvc.perform(get("/api/reports/attendance"+query)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reports/attendance/export"+query).with(user(username).roles("EMPLOYEE"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/attendance"+query).with(user("hr").roles("HR"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.totals.workMinutes").value(480));
        mvc.perform(get("/api/reports/attendance/export"+query).with(user("hr").roles("HR"))).andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment; filename=attendance.csv")).andExpect(header().string("Cache-Control","no-store"));
    }
}
