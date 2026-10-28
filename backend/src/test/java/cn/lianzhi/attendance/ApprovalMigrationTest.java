package cn.lianzhi.attendance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

class ApprovalMigrationTest {
    @Test void upgradesExistingV3WithoutChangingPublishedMigrations() throws Exception {
        String url="jdbc:h2:mem:approval-upgrade;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("3").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()){
            s.executeUpdate("INSERT INTO org_company(id,name) VALUES(1,'升级公司')");
            s.executeUpdate("INSERT INTO sys_user(id,username,password_hash,display_name,role,employee_no,company_id) VALUES(1,'upgrade','test-hash','原员工','EMPLOYEE','UPGRADE1',1)");
        }
        Flyway.configure().dataSource(url,"sa","").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()){
            try(var r=s.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE version='4' AND success=TRUE")){assertThat(r.next()).isTrue();assertThat(r.getLong(1)).isEqualTo(1);}
            try(var r=s.executeQuery("SELECT display_name FROM sys_user WHERE id=1")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("原员工");}
            s.executeUpdate("INSERT INTO approval_request(applicant_id,company_id,request_key,request_type,start_at,end_at,reason) VALUES(1,1,'valid','LEAVE','2026-10-27 09:00:00','2026-10-27 18:00:00','测试')");
            assertThatThrownBy(()->s.executeUpdate("INSERT INTO approval_request(applicant_id,company_id,request_key,request_type,start_at,reason) VALUES(1,1,'invalid','LEAVE','2026-10-27 09:00:00','缺少结束时间')")).isInstanceOf(SQLException.class);
        }
    }
}
