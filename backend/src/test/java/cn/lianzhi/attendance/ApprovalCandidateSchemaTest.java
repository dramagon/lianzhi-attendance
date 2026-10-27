package cn.lianzhi.attendance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

/** 候选SQL仅在隔离H2执行，不登记V4，不对现有开发库执行。 */
class ApprovalCandidateSchemaTest {
    @Test void candidateSchemaChecksNullsAndDoesNotActivateFlywayV4() throws Exception {
        String url="jdbc:h2:mem:approval-candidate;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").load().migrate();
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()) {
            ScriptUtils.executeSqlScript(connection,new FileSystemResource("../docs/modules/a-pending-migrations/V4__approval.sql"));
            statement.executeUpdate("INSERT INTO org_company(id,name) VALUES(1,'候选验证公司')");
            statement.executeUpdate("INSERT INTO sys_user(id,username,password_hash,display_name,role,employee_no,company_id) VALUES(1,'candidate','test-hash','候选员工','EMPLOYEE','CANDIDATE1',1)");
            statement.executeUpdate("INSERT INTO approval_request(applicant_id,company_id,request_key,request_type,start_at,end_at,reason) VALUES(1,1,'valid','LEAVE','2026-10-27 09:00:00','2026-10-27 18:00:00','候选测试')");
            assertThatThrownBy(()->statement.executeUpdate("INSERT INTO approval_request(applicant_id,company_id,request_key,request_type,start_at,reason) VALUES(1,1,'invalid','LEAVE','2026-10-27 09:00:00','缺少结束时间')")).isInstanceOf(SQLException.class);
            try(var result=statement.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE version='4'")) {
                assertThat(result.next()).isTrue(); assertThat(result.getLong(1)).isZero();
            }
        }
    }
}
