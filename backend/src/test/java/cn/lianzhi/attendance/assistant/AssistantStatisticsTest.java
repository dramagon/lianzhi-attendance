package cn.lianzhi.attendance.assistant;

import cn.lianzhi.attendance.attendance.*;
import cn.lianzhi.attendance.common.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:c-day5-stats;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test") @Transactional
class AssistantStatisticsTest {
    @Autowired AssistantStatistics statistics;@Autowired AttendanceDays days;@Autowired AttendanceSchedules schedules;@Autowired JdbcTemplate jdbc;
    long company,department,user,place;String username;LocalDate date;Authentication hr=new UsernamePasswordAuthenticationToken("hr","",List.of()),admin=new UsernamePasswordAuthenticationToken("admin","",List.of());
    @BeforeEach void setup(){
        username="cs-"+UUID.randomUUID().toString().substring(0,10);date=LocalDate.now(AttendanceRules.ZONE).plusDays(2);
        company=jdbc.queryForObject("SELECT company_id FROM sys_user WHERE username='hr'",Long.class);
        jdbc.update("INSERT INTO sys_department(company_id,name) VALUES(?,?)",company,username);department=jdbc.queryForObject("SELECT id FROM sys_department WHERE name=?",Long.class,username);
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,role,employee_no,company_id,department_id) VALUES(?,'synthetic','汇总合成员工','EMPLOYEE',?,?,?)",username,username,company,department);
        user=jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,username);
        jdbc.update("INSERT INTO workplace(company_id,name,latitude,longitude,coordinate_system,radius_meters) VALUES(?,?,31,121,'WGS84',100)",company,username);place=jdbc.queryForObject("SELECT id FROM workplace WHERE name=?",Long.class,username);
        jdbc.update("INSERT INTO employee_workplace(user_id,workplace_id) VALUES(?,?)",user,place);
        schedules.save(admin,new AttendanceSchedules.Schedule(user,date,place,new AttendanceRules.Rule(LocalTime.of(9,0),LocalTime.of(18,0),LocalTime.NOON,LocalTime.of(13,0),5,EnumSet.allOf(DayOfWeek.class)),false));
    }
    @Test void summaryIsExactlyBFieldsAndKeepsPendingSeparate(){
        var b=days.list(hr,user,date,date);var s=statistics.summarize(hr,null,department,date,date);
        assertThat(s.employeeCount()).isEqualTo(1);assertThat(s.scheduledDays()).isEqualTo(b.size());
        assertThat(s.workMinutes()).isEqualTo(b.stream().mapToLong(d->d.attendance().workMinutes()).sum());
        assertThat(s.leaveMinutes()).isEqualTo(b.stream().mapToLong(AttendanceDays.Day::leaveMinutes).sum());
        assertThat(s.overtimeMinutes()).isEqualTo(b.stream().mapToLong(AttendanceDays.Day::overtimeMinutes).sum());
        assertThat(s.pendingDays()).isEqualTo(1);assertThat(s.statuses()).containsEntry("PENDING",1L);assertThat(s.sourceUserIds()).containsExactly(user);
    }
    @Test void employeesCannotQueryCompanyAggregates(){var actor=new UsernamePasswordAuthenticationToken(username,"",List.of());assertThatThrownBy(()->statistics.summarize(actor,null,department,date,date)).isInstanceOf(BusinessException.class);}
    @Test void hrCannotForgeCompanyOrDepartmentScope(){
        jdbc.update("INSERT INTO org_company(name) VALUES(?)",username+"-foreign");long foreign=jdbc.queryForObject("SELECT id FROM org_company WHERE name=?",Long.class,username+"-foreign");
        jdbc.update("INSERT INTO sys_department(company_id,name) VALUES(?,?)",foreign,username+"-foreign");long dep=jdbc.queryForObject("SELECT id FROM sys_department WHERE name=?",Long.class,username+"-foreign");
        assertThatThrownBy(()->statistics.summarize(hr,foreign,null,date,date)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->statistics.summarize(hr,null,dep,date,date)).isInstanceOf(BusinessException.class);
    }
    @Test void disabledEmployeesAreExplicitlyExcluded(){jdbc.update("UPDATE sys_user SET enabled=FALSE WHERE id=?",user);var s=statistics.summarize(hr,null,department,date,date);assertThat(s.employeeCount()).isZero();assertThat(s.statuses()).isEmpty();}
    @Test void datesAndEmployeeBudgetAreNotSilentlyTruncated(){
        assertThatThrownBy(()->statistics.summarize(hr,null,department,date,date.plusDays(94))).isInstanceOf(BusinessException.class);
        var rows=new ArrayList<Object[]>();for(int i=0;i<101;i++)rows.add(new Object[]{username+"-"+i,username+"-"+i,company,department});
        jdbc.batchUpdate("INSERT INTO sys_user(username,password_hash,display_name,role,employee_no,company_id,department_id) VALUES(?,'synthetic','配额合成员工','EMPLOYEE',?,?,?)",rows);
        assertThatThrownBy(()->statistics.summarize(hr,null,department,date,date)).isInstanceOf(BusinessException.class);
    }
    @Test void unscheduledRangeDoesNotCreateAbsenceOrAttendanceRate(){var s=statistics.summarize(hr,null,department,date.plusDays(1),date.plusDays(1));assertThat(s.employeeCount()).isEqualTo(1);assertThat(s.scheduledDays()).isZero();assertThat(s.statuses()).isEmpty();assertThat(s.pendingDays()).isZero();}
}
