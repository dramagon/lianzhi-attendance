package cn.lianzhi.attendance.attendance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class AttendanceMysqlRestartIT {
    @Autowired JdbcTemplate jdbc;
    @Test void newJvmSeesCommittedEventsSchedulesAndAdjustmentsAfterMysqlRestart() throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(connection.getCatalog()).isEqualTo("lianzhi_b_verify");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_event",Long.class)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_schedule",Long.class)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_adjustment",Long.class)).isGreaterThan(0);
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank",String.class)).containsExactly("1","2","3","4","4.1","5","6");
    }
}
