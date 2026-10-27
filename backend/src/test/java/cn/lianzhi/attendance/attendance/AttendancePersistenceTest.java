package cn.lianzhi.attendance.attendance;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Uses only an isolated test DB; never applies staged V3 to the live database. */
class AttendancePersistenceTest {
    JdbcTemplate jdbc; AttendanceStore store; QrTokens tokens; AttendanceService service;
    AttendanceDirectory directory; TransactionTemplate tx;
    final Instant now=Instant.parse("2026-10-26T01:00:00Z");
    @BeforeEach void database() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:b-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);jdbc.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY, enabled BOOLEAN NOT NULL)");
        jdbc.update("INSERT INTO sys_user VALUES(1,TRUE),(2,TRUE)");
        jdbc.execute("CREATE TABLE workplace(id BIGINT PRIMARY KEY)");jdbc.update("INSERT INTO workplace VALUES(8)");
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/V3__attendance.sql")).execute(ds);
        store=new AttendanceStore(jdbc);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tokens=new QrTokens(new byte[32],Clock.fixed(now,ZoneOffset.UTC));
        directory=new AttendanceDirectory(){
            public List<Workplace> workplaces(long user){return List.of(new Workplace(8,"测试场所",new GeoFence.Point(0,0,"WGS84"),200,false));}
            public Assignment forPunch(long user,long place,Instant time){if(place!=8)throw new org.springframework.security.access.AccessDeniedException("无权访问场所");return new Assignment(workplaces(user).get(0),new AttendanceRules.Rule(LocalTime.of(9,0),LocalTime.of(18,0),null,null,0,Set.of(DayOfWeek.MONDAY)),LocalDate.of(2026,10,26),false);}
            public void requireManager(long user,long place){throw new org.springframework.security.access.AccessDeniedException("无管理权限");}
        };service=serviceAt(now);
    }
    AttendanceService serviceAt(Instant time){var factory=new StaticListableBeanFactory(Map.of("directory",directory));return new AttendanceService(factory.getBeanProvider(AttendanceDirectory.class),store,tokens,Clock.fixed(time,ZoneOffset.UTC));}
    AttendanceService.Punch punch(String type,String token,String request){return new AttendanceService.Punch(type,token,request,new GeoFence.Point(0,0,"WGS84"),10.0);}
    AttendanceService.EventView submit(AttendanceService target,long user,AttendanceService.Punch p){return tx.execute(s->target.punch(user,p));}
    @Test void eventPersistsAndRetryIsIdempotent(){var p=punch("IN",tokens.issue(8).token(),UUID.randomUUID().toString());var a=submit(service,1,p);var b=submit(service,1,p);assertThat(a.id()).isEqualTo(b.id());assertThat(new AttendanceStore(jdbc).day(1,LocalDate.of(2026,10,26))).hasSize(1);assertThat(service.records(2,LocalDate.of(2026,10,1),LocalDate.of(2026,10,26),1,20).total()).isZero();}
    @Test void sharedChallengeSupportsDifferentUsers(){String token=tokens.issue(8).token();submit(service,1,punch("IN",token,UUID.randomUUID().toString()));submit(service,2,punch("IN",token,UUID.randomUUID().toString()));assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_event",Integer.class)).isEqualTo(2);}
    @Test void replayCannotTurnSignInIntoSignOut(){String token=tokens.issue(8).token();submit(service,1,punch("IN",token,UUID.randomUUID().toString()));assertThatThrownBy(()->submit(serviceAt(now.plusSeconds(30)),1,punch("OUT",token,UUID.randomUUID().toString()))).isInstanceOf(AttendanceProblem.class);assertThat(store.day(1,LocalDate.of(2026,10,26))).hasSize(1);}
    @Test void checkoutRequiresSignInAndNewChallenge(){assertThatThrownBy(()->submit(service,1,punch("OUT",tokens.issue(8).token(),UUID.randomUUID().toString()))).isInstanceOf(IllegalArgumentException.class);submit(service,1,punch("IN",tokens.issue(8).token(),UUID.randomUUID().toString()));submit(serviceAt(now.plusSeconds(30)),1,punch("OUT",tokens.issue(8).token(),UUID.randomUUID().toString()));assertThat(store.day(1,LocalDate.of(2026,10,26))).hasSize(2);}
    @Test void disabledAccountsAndScopeAreRejected(){jdbc.update("UPDATE sys_user SET enabled=FALSE WHERE id=1");assertThatThrownBy(()->submit(service,1,punch("IN",tokens.issue(8).token(),UUID.randomUUID().toString()))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);assertThatThrownBy(()->submit(service,2,punch("IN",tokens.issue(99).token(),UUID.randomUUID().toString()))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);assertThatThrownBy(()->service.issue(2,8)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);}
    @Test void invalidPaginationIsRejected(){assertThatThrownBy(()->service.records(1,LocalDate.of(2026,1,1),LocalDate.of(2026,10,26),1,20)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.records(1,LocalDate.of(2026,10,1),LocalDate.of(2026,10,26),0,200)).isInstanceOf(IllegalArgumentException.class);}
    @Test void simultaneousPunchesCreateOneEvent() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {var tasks=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++){String token=tokens.issue(8).token();tasks.add(pool.submit(()->{start.await();try{submit(service,1,punch("IN",token,UUID.randomUUID().toString()));return true;}catch(AttendanceProblem expected){return false;}}));}start.countDown();int success=0;for(var f:tasks)if(f.get(10,TimeUnit.SECONDS))success++;assertThat(success).isEqualTo(1);assertThat(store.day(1,LocalDate.of(2026,10,26))).hasSize(1);}finally{pool.shutdownNow();}
    }
}
