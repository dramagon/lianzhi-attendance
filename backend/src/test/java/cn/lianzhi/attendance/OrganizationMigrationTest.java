package cn.lianzhi.attendance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;

class OrganizationMigrationTest {
    @Test void upgradeFromV1PreservesExistingIdentityAndLeavesCompanyUnassigned() throws Exception {
        String url="jdbc:h2:mem:upgrade-v2;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("1").load().migrate();
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()) {
            statement.executeUpdate("INSERT INTO sys_department(id,name) VALUES(41,'原有部门')");
            statement.executeUpdate("INSERT INTO sys_user(id,username,password_hash,display_name,role,employee_no,department_id) VALUES(91,'legacy','test-hash','原有员工','EMPLOYEE','OLD091',41)");
        }
        Flyway.configure().dataSource(url,"sa","").load().migrate();
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement();var result=statement.executeQuery("SELECT u.id,u.password_hash,u.department_id,u.company_id,d.name FROM sys_user u JOIN sys_department d ON d.id=u.department_id WHERE u.username='legacy'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong("id")).isEqualTo(91);
            assertThat(result.getString("password_hash")).isEqualTo("test-hash");
            assertThat(result.getLong("department_id")).isEqualTo(41);
            assertThat(result.getObject("company_id")).isNull();
            assertThat(result.getString("name")).isEqualTo("原有部门");
        }
    }
}
