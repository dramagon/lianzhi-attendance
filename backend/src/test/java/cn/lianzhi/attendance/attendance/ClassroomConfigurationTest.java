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

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:classroom;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles({"test","classroom"}) @AutoConfigureMockMvc
class ClassroomConfigurationTest {
    @Autowired MockMvc mvc;
    @Test void loginShellIsPublicButApisStillRequireAuthentication() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/attendance/presentation")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/api/attendance/presentation")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/attendance/me/records?from=2026-11-01&to=2026-11-03")).andExpect(status().isUnauthorized());
    }
    @Test @WithMockUser(username="employee",roles="EMPLOYEE")
    void classroomIsExplicitAndNeverReportsFaceSuccessOrBypassesCsrf() throws Exception {
        mvc.perform(get("/api/attendance/presentation")).andExpect(status().isOk()).andExpect(jsonPath("$.data.classroom").value(true));
        mvc.perform(get("/api/face/status")).andExpect(jsonPath("$.data.available").value(false));
        mvc.perform(get("/api/assistant/status")).andExpect(jsonPath("$.data.modelConfiguration").value("OFFLINE_EXTRACTIVE"));
        mvc.perform(post("/api/attendance/me/punches").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/accounts")).andExpect(status().isForbidden());
    }
}
