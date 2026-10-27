package cn.lianzhi.attendance;

import cn.lianzhi.attendance.common.BusinessException;
import cn.lianzhi.attendance.employee.EmployeeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:admin-race;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class AdministratorConcurrencyTest {
    @Autowired EmployeeService employees;
    @Autowired JdbcTemplate jdbc;
    @Test void concurrentDemotionsCannotRemoveBothAdministrators() throws Exception {
        var admin=new UsernamePasswordAuthenticationToken("admin","unused");
        Long company=jdbc.queryForObject("SELECT company_id FROM sys_user WHERE username='admin'",Long.class);
        Long adminId=jdbc.queryForObject("SELECT id FROM sys_user WHERE username='admin'",Long.class);
        var second=employees.save(admin,null,new EmployeeService.EmployeeInput("secondadmin","第二管理员","ADMIN002","ADMIN",company,null,true,"TestPassword123!",List.of()));
        var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> first=()->demote(start,"admin",adminId,"DEMO001",company);
            Callable<Boolean> other=()->demote(start,"secondadmin",second.userId(),"ADMIN002",company);
            var a=pool.submit(first); var b=pool.submit(other); start.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE role='ADMIN' AND enabled=TRUE",Long.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    private boolean demote(CountDownLatch start,String username,Long id,String number,Long company) throws Exception {
        start.await();
        try {
            employees.save(new UsernamePasswordAuthenticationToken(username,"unused"),id,new EmployeeService.EmployeeInput(username,"原管理员",number,"EMPLOYEE",company,null,true,"",List.of()));
            return true;
        } catch (BusinessException error) {
            assertThat(error.getMessage()).contains("至少一个"); return false;
        }
    }
}
