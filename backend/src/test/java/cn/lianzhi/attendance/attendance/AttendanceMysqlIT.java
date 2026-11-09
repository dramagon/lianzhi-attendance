package cn.lianzhi.attendance.attendance;

import cn.lianzhi.attendance.approval.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in IT: only the harness-created MySQL database; not part of default H2 tests. */
@SpringBootTest
@ActiveProfiles("test")
class AttendanceMysqlIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired AttendanceSchedules schedules;
    @Autowired AttendanceService service;
    @Autowired AttendanceDays days;
    @Autowired QrTokens tokens;
    @Autowired PlatformTransactionManager transactions;
    long user,place,company,admin;
    String username;
    LocalDate date=LocalDate.now(AttendanceRules.ZONE);
    Authentication manager=new UsernamePasswordAuthenticationToken("admin","",List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    @BeforeEach void fixture() throws Exception {
        try(var c=jdbc.getDataSource().getConnection()) {
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(c.getCatalog()).isEqualTo("lianzhi_b_verify");
        }
        username="mysql-it-"+UUID.randomUUID().toString().substring(0,12);
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,role,employee_no,company_id,enabled) SELECT ?,password_hash,'MySQL测试员工','EMPLOYEE',?,company_id,TRUE FROM sys_user WHERE username='employee'",username,username);
        user=jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,username);
        company=jdbc.queryForObject("SELECT company_id FROM sys_user WHERE id=?",Long.class,user);
        admin=jdbc.queryForObject("SELECT id FROM sys_user WHERE username='admin'",Long.class);
        jdbc.update("INSERT INTO workplace(company_id,name,latitude,longitude,coordinate_system,radius_meters) VALUES(?,?,31.2,121.5,'WGS84',200)",company,username);
        place=jdbc.queryForObject("SELECT id FROM workplace WHERE name=?",Long.class,username);
        jdbc.update("INSERT INTO employee_workplace(user_id,workplace_id) VALUES(?,?)",user,place);
        schedules.save(manager,new AttendanceSchedules.Schedule(user,date,place,new AttendanceRules.Rule(LocalTime.MIDNIGHT,LocalTime.of(23,59),null,null,0,EnumSet.allOf(DayOfWeek.class)),false));
    }
    Authentication worker(){return new UsernamePasswordAuthenticationToken(username,"",List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));}
    AttendanceService.Punch input(String id){return new AttendanceService.Punch("IN",tokens.issue(place).token(),id,new GeoFence.Point(31.2,121.5,"WGS84"),10.0);}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    List<Long> race(boolean sameRequest) throws Exception {
        var first=input(UUID.randomUUID().toString());var second=sameRequest?first:input(UUID.randomUUID().toString());
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            var tasks=new ArrayList<Future<Long>>();
            for(var request:List.of(first,second))tasks.add(pool.submit(()->{
                SecurityContextHolder.getContext().setAuthentication(worker());
                try{start.await();return service.punch(user,request).id();}
                catch(AttendanceProblem e){if(e.code().equals("DUPLICATE_PUNCH"))return -1L;throw e;}
                finally{SecurityContextHolder.clearContext();}
            }));
            start.countDown();var ids=new ArrayList<Long>();for(var task:tasks)ids.add(task.get(15,TimeUnit.SECONDS));return ids;
        }finally{pool.shutdownNow();}
    }
    @Test void concurrentDistinctRequestsInsertOnlyOneEvent() throws Exception {
        var ids=race(false);assertThat(ids.stream().filter(id->id>0).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_event WHERE user_id=?",Long.class,user)).isEqualTo(1);
    }
    @Test void concurrentIdenticalRequestsReturnSameEvent() throws Exception {
        var ids=race(true);assertThat(ids).allMatch(id->id>0);assertThat(ids.get(0)).isEqualTo(ids.get(1));
    }
    ApprovalAttendancePort.Adjustment leave(long id){return new ApprovalAttendancePort.Adjustment(id,user,company,new ApprovalRules.Draft(ApprovalRules.Type.LEAVE,date.atStartOfDay(),date.atTime(23,59),null,null,"MySQL审批测试"),admin,LocalDateTime.now(AttendanceRules.ZONE));}
    @Test void callerTransactionRollsBackAdjustmentAndCallerWrites(){
        long approval=800000+user;
        var transaction=new TransactionTemplate(transactions);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThatThrownBy(()->transaction.execute(status->{
            jdbc.update("UPDATE sys_user SET display_name='应回滚' WHERE id=?",user);
            days.apply(manager,leave(approval));
            throw new IllegalStateException("模拟A后续审计失败");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_adjustment WHERE approval_id=?",Long.class,approval)).isZero();
        assertThat(jdbc.queryForObject("SELECT display_name FROM sys_user WHERE id=?",String.class,user)).isEqualTo("MySQL测试员工");
    }
    @Test void committedAdjustmentIsIdempotentAndTraceableAfterNewConnection(){
        long approval=900000+user;days.apply(manager,leave(approval));days.apply(manager,leave(approval));
        var day=days.list(manager,user,date,date).get(0);assertThat(day.attendance().status()).isEqualTo("ON_LEAVE");
        assertThat(day.approvalIds()).containsExactly(approval);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_adjustment WHERE approval_id=?",Long.class,approval)).isEqualTo(1);
    }
    @Test void concurrentSameApprovalIsIdempotent() throws Exception {
        long approval=1000000+user;var a=leave(approval);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            var first=pool.submit(()->{start.await();days.apply(manager,a);return true;});
            var second=pool.submit(()->{start.await();days.apply(manager,a);return true;});
            start.countDown();assertThat(first.get(15,TimeUnit.SECONDS)).isTrue();assertThat(second.get(15,TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_adjustment WHERE approval_id=?",Long.class,approval)).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }

}
