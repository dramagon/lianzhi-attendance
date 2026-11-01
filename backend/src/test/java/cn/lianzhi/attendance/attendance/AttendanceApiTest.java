package cn.lianzhi.attendance.attendance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AttendanceApiTest {
    @Autowired MockMvc mvc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired AttendanceStore store;
    static final String INPUT="""
      {"date":"2026-10-26","rule":{"start":"09:00","end":"18:00","breakStart":"12:00","breakEnd":"13:00","graceMinutes":5,"workdays":["MONDAY"]},"checkIn":"2026-10-26T09:12:00","checkOut":"2026-10-26T18:00:00","holiday":false,"finalized":true}
      """;
    @Test void anonymousCannotAccessB() throws Exception {for(String url:new String[]{"/api/attendance/status","/api/face/status","/api/attendance/me/records?from=2026-10-01&to=2026-10-26","/api/attendance/me/punches/requests/00000000-0000-0000-0000-000000000000"})mvc.perform(get(url)).andExpect(status().isUnauthorized());}
    @Test @WithMockUser(username="employee",roles="EMPLOYEE")
    @org.springframework.transaction.annotation.Transactional
    void receiptOnlyReturnsAuthenticatedOwnersEventAndNoPrivateToken() throws Exception {
        long employee=jdbc.queryForObject("SELECT id FROM sys_user WHERE username='employee'",Long.class);
        long company=jdbc.queryForObject("SELECT company_id FROM sys_user WHERE id=?",Long.class,employee);
        jdbc.update("INSERT INTO workplace(company_id,name,latitude,longitude,coordinate_system,radius_meters) VALUES(?,'回执API测试',31.2,121.5,'WGS84',200)",company);
        long place=jdbc.queryForObject("SELECT id FROM workplace WHERE name='回执API测试'",Long.class);
        String requestId=java.util.UUID.randomUUID().toString();
        var event=store.append(employee,place,java.time.LocalDate.of(2026,10,1),"IN",java.time.Instant.parse("2026-10-01T01:00:00Z"),requestId,"private-nonce",0);
        String path="/api/attendance/me/punches/requests/"+requestId;
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.data.found").value(true))
            .andExpect(jsonPath("$.data.event.id").value(event.id())).andExpect(jsonPath("$.data.event.requestId").doesNotExist())
            .andExpect(jsonPath("$.data.event.nonce").doesNotExist());
        mvc.perform(get(path).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.found").value(false)).andExpect(jsonPath("$.data.event").isEmpty());
        mvc.perform(get("/api/attendance/me/punches/requests/invalid")).andExpect(status().isBadRequest());
    }
    @Test @WithMockUser(username="employee",roles="EMPLOYEE") void previewUsesRealRules() throws Exception {mvc.perform(post("/api/attendance/rules/preview").with(csrf()).contentType("application/json").content(INPUT)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("LATE")).andExpect(jsonPath("$.data.workMinutes").value(468));}
    @Test @WithMockUser(username="employee",roles="EMPLOYEE") void writesRequireCsrf() throws Exception {mvc.perform(post("/api/attendance/rules/preview").contentType("application/json").content(INPUT)).andExpect(status().isForbidden());}
    @Test @WithMockUser(username="employee",roles="EMPLOYEE") void integratedStorageIsReadyButInvalidPunchCannotCreateHistory() throws Exception {mvc.perform(get("/api/attendance/status")).andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(true)).andExpect(jsonPath("$.data.rulesAvailable").value(true));mvc.perform(post("/api/attendance/me/punches").with(csrf()).contentType("application/json").content("{}")).andExpect(status().isBadRequest());mvc.perform(get("/api/attendance/me/records?from=2026-10-01&to=2026-10-26")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));}
    @Test @WithMockUser(username="employee",roles="EMPLOYEE") void employeeCannotIssueWorkplaceChallenge() throws Exception {mvc.perform(get("/api/attendance/managed/workplaces")).andExpect(status().isForbidden());mvc.perform(post("/api/attendance/workplaces/1/challenge").with(csrf())).andExpect(status().isForbidden());}
    @Test @WithMockUser(username="employee",roles="EMPLOYEE") void malformedInputsAreReadable() throws Exception {mvc.perform(post("/api/attendance/rules/preview").with(csrf()).contentType("application/json").content(INPUT.replace("2026-10-26","wrong"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ATTENDANCE_INVALID_INPUT"));mvc.perform(post("/api/attendance/rules/preview").with(csrf()).contentType("application/json").content("null")).andExpect(status().isBadRequest());}
    @Test @WithMockUser(username="employee",roles="EMPLOYEE") void faceNeverReportsFakeSuccess() throws Exception {mvc.perform(get("/api/face/status")).andExpect(status().isOk()).andExpect(jsonPath("$.data.comparison").value(false)).andExpect(jsonPath("$.data.liveness").value(false));mvc.perform(post("/api/face/verification").with(csrf())).andExpect(status().isServiceUnavailable());}
}
