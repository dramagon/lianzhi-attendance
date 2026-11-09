package cn.lianzhi.attendance.face;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

class FaceMigrationTest {
    @Test void upgradesV41WithExistingAttendanceAndApprovedApplicationWithoutRewritingFacts() throws Exception {
        String url="jdbc:h2:mem:face-v5-upgrade;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("4.1").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()) {
            s.executeUpdate("INSERT INTO org_company(id,name) VALUES(1,'V5升级测试公司')");
            s.executeUpdate("INSERT INTO sys_user(id,username,password_hash,display_name,role,employee_no,company_id) VALUES(1,'upgrade-face','test-only','原员工','EMPLOYEE','FACE_UPGRADE',1)");
            s.executeUpdate("INSERT INTO workplace(id,company_id,name,latitude,longitude,coordinate_system,radius_meters) VALUES(1,1,'原场所',31.2,121.5,'WGS84',200)");
            s.executeUpdate("INSERT INTO attendance_event(user_id,workplace_id,business_date,event_type,occurred_at,request_id,qr_nonce,distance_meters) VALUES(1,1,'2026-11-03','IN','2026-11-03 01:00:00.123456','old-request','old-nonce',0)");
            s.executeUpdate("INSERT INTO approval_request(id,applicant_id,company_id,request_key,request_type,state,start_at,end_at,reason,version) VALUES(1,1,1,'old-approval','LEAVE','APPROVED','2026-11-04 09:00:00','2026-11-04 18:00:00','升级前已审批',1)");
            s.executeUpdate("INSERT INTO approval_transition(approval_id,from_state,to_state,actor_id,reason,occurred_at,version) VALUES(1,'PENDING','APPROVED',1,'原审计','2026-11-03 10:00:00',1)");
        }
        var flyway=Flyway.configure().dataSource(url,"sa","").target("5").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();assertThat(flyway.migrate().migrationsExecuted).isZero();
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()) {
            try(var rs=s.executeQuery("SELECT request_id,occurred_at FROM attendance_event")) {
                assertThat(rs.next()).isTrue();assertThat(rs.getString(1)).isEqualTo("old-request");
                assertThat(rs.getTimestamp(2).getNanos()).isEqualTo(123456000);
                assertThat(rs.next()).isFalse();
            }
            try(var rs=s.executeQuery("SELECT state,version FROM approval_request")) {
                assertThat(rs.next()).isTrue();assertThat(rs.getString(1)).isEqualTo("APPROVED");assertThat(rs.getLong(2)).isEqualTo(1);
            }
            for(String table:new String[]{"face_enrollment","face_challenge","face_audit"}) {
                try(var rs=s.executeQuery("SELECT COUNT(*) FROM "+table)) { assertThat(rs.next()).isTrue();assertThat(rs.getLong(1)).isZero(); }
            }
            try(var rs=s.executeQuery("SELECT COUNT(*) FROM approval_transition")) { assertThat(rs.next()).isTrue();assertThat(rs.getLong(1)).isEqualTo(1); }
        }
    }
}
