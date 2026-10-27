package cn.lianzhi.attendance.attendance;

import cn.lianzhi.attendance.approval.*;
import cn.lianzhi.attendance.organization.DataScope;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=${LIANZHI_ATTENDANCE_TEST_URL:jdbc:h2:mem:b-workflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}")
@ActiveProfiles("test")
@Transactional
@WithMockUser(username="employee",roles="EMPLOYEE")
class AttendanceWorkflowTest {
    @Autowired JdbcTemplate jdbc; @Autowired AttendanceSchedules schedules; @Autowired AttendanceDays days;
    @Autowired AttendanceStore store; @Autowired ObjectProvider<AttendanceDirectory> directory;
    long user,company,place,admin; LocalDate date=LocalDate.now(AttendanceRules.ZONE);
    Authentication manager=new UsernamePasswordAuthenticationToken("admin","",List.of());
    MutableClock clock; QrTokens tokens; AttendanceService service;
    static class MutableClock extends Clock { Instant value; MutableClock(Instant v){value=v;} public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value;} }
    @BeforeEach void prepare(){
        if (System.getenv("LIANZHI_ATTENDANCE_TEST_URL") != null) {
            try (var connection = jdbc.getDataSource().getConnection()) {
                assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            } catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
        }
        user=jdbc.queryForObject("SELECT id FROM sys_user WHERE username='employee'",Long.class);
        admin=jdbc.queryForObject("SELECT id FROM sys_user WHERE username='admin'",Long.class);
        company=jdbc.queryForObject("SELECT company_id FROM sys_user WHERE id=?",Long.class,user);
        jdbc.update("INSERT INTO workplace(company_id,name,latitude,longitude,coordinate_system,radius_meters) VALUES(?,'B测试场所',31.2,121.5,'WGS84',200)",company);
        place=jdbc.queryForObject("SELECT id FROM workplace WHERE name='B测试场所'",Long.class);
        jdbc.update("INSERT INTO employee_workplace(user_id,workplace_id) VALUES(?,?)",user,place);
        clock=new MutableClock(date.atTime(9,0).atZone(AttendanceRules.ZONE).toInstant());tokens=new QrTokens(new byte[32],clock);service=new AttendanceService(directory,store,tokens,clock);
        schedules.save(manager,schedule(date));
    }
    AttendanceSchedules.Schedule schedule(LocalDate day){return new AttendanceSchedules.Schedule(user,day,place,new AttendanceRules.Rule(LocalTime.of(9,0),LocalTime.of(18,0),LocalTime.NOON,LocalTime.of(13,0),5,EnumSet.allOf(DayOfWeek.class)),false);}
    AttendanceService.EventView punch(String type){return service.punch(user,new AttendanceService.Punch(type,tokens.issue(place).token(),UUID.randomUUID().toString(),new GeoFence.Point(31.2,121.5,"WGS84"),10.0));}
    ApprovalAttendancePort.Adjustment adjustment(long id,ApprovalRules.Draft draft){return new ApprovalAttendancePort.Adjustment(id,user,company,draft,admin,date.atTime(19,0));}
    @Test void actualDirectorySchedulePunchAndDailyQuery(){
        assertThat(service.workplaces(user)).hasSize(1);punch("IN");clock.value=date.atTime(18,0).atZone(AttendanceRules.ZONE).toInstant();punch("OUT");
        var daily=days.list(SecurityContextHolder.getContext().getAuthentication(),user,date,date).get(0);
        assertThat(daily.attendance().status()).isEqualTo("NORMAL");assertThat(daily.attendance().workMinutes()).isEqualTo(480);assertThat(store.day(user,date)).hasSize(2);
    }
    @Test void missingShiftAndUnboundWorkplaceFailClosed(){
        clock.value=date.plusDays(3).atTime(9,0).atZone(AttendanceRules.ZONE).toInstant();assertThatThrownBy(()->punch("IN")).isInstanceOf(AttendanceProblem.class);
        jdbc.update("DELETE FROM employee_workplace WHERE user_id=?",user);assertThatThrownBy(()->punch("IN")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void employeeCannotChangeSchedulesOrQueryOtherEmployees(){
        var actor=SecurityContextHolder.getContext().getAuthentication();assertThatThrownBy(()->schedules.save(actor,schedule(date.plusDays(1)))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->days.list(actor,admin,date,date)).isInstanceOf(RuntimeException.class);
    }
    @Test void eventLocksScheduleAgainstSilentRuleChanges(){punch("IN");assertThatThrownBy(()->schedules.save(manager,schedule(date))).isInstanceOf(AttendanceProblem.class);}
    @Test void approvedMissingPunchIsIdempotentAndPreservesRawEvents(){
        clock.value=date.atTime(18,0).atZone(AttendanceRules.ZONE).toInstant();
        // A trusted approved correction is applied without forging an original event.
        var in=adjustment(100,new ApprovalRules.Draft(ApprovalRules.Type.MISSING_PUNCH,date.atTime(9,0),null,ApprovalRules.PunchType.IN,place,"补签到"));
        days.apply(manager,in);days.apply(manager,in);
        var out=adjustment(101,new ApprovalRules.Draft(ApprovalRules.Type.MISSING_PUNCH,date.atTime(18,0),null,ApprovalRules.PunchType.OUT,place,"补签退"));days.apply(manager,out);
        var d=days.list(manager,user,date,date).get(0);assertThat(d.attendance().workMinutes()).isEqualTo(480);assertThat(d.approvalIds()).containsExactly(100L,101L);assertThat(store.day(user,date)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_adjustment WHERE user_id=?",Integer.class,user)).isEqualTo(2);
    }
    @Test void fullDayLeaveCreditsWorkingTimeExcludingBreak(){
        days.apply(manager,adjustment(102,new ApprovalRules.Draft(ApprovalRules.Type.LEAVE,date.atTime(9,0),date.atTime(18,0),null,null,"年假")));
        var d=days.list(manager,user,date,date).get(0);assertThat(d.leaveMinutes()).isEqualTo(480);assertThat(d.attendance().status()).isEqualTo("ON_LEAVE");assertThat(d.attendance().workMinutes()).isZero();
    }
    @Test void correctionRejectsSelfApprovalAndChangedPayload(){
        var a=adjustment(103,new ApprovalRules.Draft(ApprovalRules.Type.LEAVE,date.atTime(9,0),date.atTime(10,0),null,null,"请假"));days.apply(manager,a);
        assertThatThrownBy(()->days.apply(manager,adjustment(103,new ApprovalRules.Draft(ApprovalRules.Type.LEAVE,date.atTime(9,0),date.atTime(11,0),null,null,"改变内容")))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void approvedCheckInAllowsActualCheckOut(){
        days.apply(manager,adjustment(110,new ApprovalRules.Draft(ApprovalRules.Type.MISSING_PUNCH,date.atTime(9,0),null,ApprovalRules.PunchType.IN,place,"漏签")));
        clock.value=date.atTime(18,0).atZone(AttendanceRules.ZONE).toInstant();punch("OUT");
        assertThat(days.list(manager,user,date,date).get(0).attendance().workMinutes()).isEqualTo(480);
        assertThat(store.day(user,date)).hasSize(1);
    }
    @Test void approvedOvertimeCountsOnlyActualAttendance(){
        days.apply(manager,adjustment(111,new ApprovalRules.Draft(ApprovalRules.Type.OVERTIME,date.atTime(18,0),date.atTime(20,0),null,null,"项目加班")));
        punch("IN");clock.value=date.atTime(19,0).atZone(AttendanceRules.ZONE).toInstant();punch("OUT");
        var d=days.list(manager,user,date,date).get(0);assertThat(d.overtimeMinutes()).isEqualTo(60);assertThat(d.attendance().workMinutes()).isEqualTo(480);
    }
    @Test void leaveExemptsOnlyApprovedLateness(){
        days.apply(manager,adjustment(112,new ApprovalRules.Draft(ApprovalRules.Type.LEAVE,date.atTime(9,0),date.atTime(10,0),null,null,"请假1小时")));
        clock.value=date.atTime(11,0).atZone(AttendanceRules.ZONE).toInstant();punch("IN");clock.value=date.atTime(18,0).atZone(AttendanceRules.ZONE).toInstant();punch("OUT");
        var d=days.list(manager,user,date,date).get(0);assertThat(d.leaveMinutes()).isEqualTo(60);assertThat(d.attendance().lateMinutes()).isEqualTo(60);assertThat(d.attendance().workMinutes()).isEqualTo(360);
    }
    @Test void restDayRequiresApprovedOvertimeAndDoesNotBecomeNormalWork(){
        var s=schedule(date);schedules.save(manager,new AttendanceSchedules.Schedule(user,date,place,s.rule(),true));
        assertThatThrownBy(()->punch("IN")).isInstanceOf(IllegalArgumentException.class);
        days.apply(manager,adjustment(113,new ApprovalRules.Draft(ApprovalRules.Type.OVERTIME,date.atTime(10,0),date.atTime(12,0),null,null,"休息日加班")));
        clock.value=date.atTime(10,0).atZone(AttendanceRules.ZONE).toInstant();punch("IN");clock.value=date.atTime(12,0).atZone(AttendanceRules.ZONE).toInstant();punch("OUT");
        var d=days.list(manager,user,date,date).get(0);assertThat(d.overtimeMinutes()).isEqualTo(120);assertThat(d.attendance().workMinutes()).isZero();assertThat(d.attendance().lateMinutes()).isZero();assertThat(d.attendance().status()).isEqualTo("REST_DAY");
    }

}
