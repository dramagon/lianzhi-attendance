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
    static final String INPUT="""
      {"date":"2026-10-26","rule":{"start":"09:00","end":"18:00","breakStart":"12:00","breakEnd":"13:00","graceMinutes":5,"workdays":["MONDAY"]},"checkIn":"2026-10-26T09:12:00","checkOut":"2026-10-26T18:00:00","holiday":false,"finalized":true}
      """;
    @Test void anonymousCannotAccessB() throws Exception {for(String url:new String[]{"/api/attendance/status","/api/face/status","/api/attendance/me/records?from=2026-10-01&to=2026-10-26"})mvc.perform(get(url)).andExpect(status().isUnauthorized());}
    @Test @WithMockUser(username="employee",roles="EMPLOYEE") void previewUsesRealRules() throws Exception {mvc.perform(post("/api/attendance/rules/preview").with(csrf()).contentType("application/json").content(INPUT)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("LATE")).andExpect(jsonPath("$.data.workMinutes").value(468));}
    @Test @WithMockUser(username="employee",roles="EMPLOYEE") void writesRequireCsrf() throws Exception {mvc.perform(post("/api/attendance/rules/preview").contentType("application/json").content(INPUT)).andExpect(status().isForbidden());}
    @Test @WithMockUser(username="employee",roles="EMPLOYEE") void absentAClosesWritesAndDoesNotFakeHistory() throws Exception {mvc.perform(get("/api/attendance/status")).andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(false)).andExpect(jsonPath("$.data.rulesAvailable").value(true));mvc.perform(post("/api/attendance/me/punches").with(csrf()).contentType("application/json").content("{}")).andExpect(status().isServiceUnavailable());mvc.perform(get("/api/attendance/me/records?from=2026-10-01&to=2026-10-26")).andExpect(status().isServiceUnavailable());}
    @Test @WithMockUser(username="employee",roles="EMPLOYEE") void employeeCannotIssueWorkplaceChallenge() throws Exception {mvc.perform(post("/api/attendance/workplaces/1/challenge").with(csrf())).andExpect(status().isForbidden());}
    @Test @WithMockUser(username="employee",roles="EMPLOYEE") void malformedInputsAreReadable() throws Exception {mvc.perform(post("/api/attendance/rules/preview").with(csrf()).contentType("application/json").content(INPUT.replace("2026-10-26","wrong"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ATTENDANCE_INVALID_INPUT"));mvc.perform(post("/api/attendance/rules/preview").with(csrf()).contentType("application/json").content("null")).andExpect(status().isBadRequest());}
    @Test @WithMockUser(username="employee",roles="EMPLOYEE") void faceNeverReportsFakeSuccess() throws Exception {mvc.perform(get("/api/face/status")).andExpect(status().isOk()).andExpect(jsonPath("$.data.comparison").value(false)).andExpect(jsonPath("$.data.liveness").value(false));mvc.perform(post("/api/face/verification").with(csrf())).andExpect(status().isServiceUnavailable());}
}
