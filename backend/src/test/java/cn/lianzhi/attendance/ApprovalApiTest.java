package cn.lianzhi.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
class ApprovalApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    Map<String,Object> draft() { return new HashMap<>(Map.of("type","LEAVE","startAt","2026-10-27T09:00:00","endAt","2026-10-27T18:00:00","reason","申请事由")); }
    @Test void statusIsHonestAndAnonymousCannotAccess() throws Exception {
        mvc.perform(get("/api/approvals/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/approvals/status").with(user("employee"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(true)).andExpect(jsonPath("$.data.validationAvailable").value(true)).andExpect(jsonPath("$.data.phase").value("CONNECTED"));
    }
    @Test void previewUsesAuthenticatedIdentityAndHasNoPersistenceSideEffects() throws Exception {
        var input=draft(); input.put("userId",99999); input.put("companyId",99999);
        Long expected=jdbc.queryForObject("SELECT id FROM sys_user WHERE username='employee'",Long.class);
        Long before=jdbc.queryForObject("SELECT COUNT(*) FROM approval_request WHERE applicant_id=?",Long.class,expected);
        mvc.perform(post("/api/approvals/preview").with(user("employee")).with(csrf()).contentType("application/json").content(json.writeValueAsString(input)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.applicantId").value(expected)).andExpect(jsonPath("$.data.validated").value(true)).andExpect(jsonPath("$.data.stored").value(false)).andExpect(jsonPath("$.data.attendanceAdjusted").value(false));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_request WHERE applicant_id=?",Long.class,expected)).isEqualTo(before);
    }
    @Test void previewRequiresCsrfAndRejectsBadInput() throws Exception {
        mvc.perform(post("/api/approvals/preview").with(user("employee")).contentType("application/json").content(json.writeValueAsString(draft()))).andExpect(status().isForbidden());
        var input=draft(); input.put("endAt","2026-10-26T18:00:00");
        mvc.perform(post("/api/approvals/preview").with(user("employee")).with(csrf()).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isBadRequest());
    }
    @Test void missingPunchRequiresOwnActiveWorkplaceBinding() throws Exception {
        Long employee=jdbc.queryForObject("SELECT id FROM sys_user WHERE username='employee'",Long.class);
        Long company=jdbc.queryForObject("SELECT company_id FROM sys_user WHERE id=?",Long.class,employee);
        jdbc.update("INSERT INTO workplace(company_id,name,latitude,longitude,coordinate_system,radius_meters) VALUES(?,'审批校验场所',31,121,'WGS84',100)",company);
        Long place=jdbc.queryForObject("SELECT id FROM workplace WHERE name='审批校验场所'",Long.class);
        var input=Map.of("type","MISSING_PUNCH","startAt","2026-10-27T09:00:00","punchType","IN","workplaceId",place,"reason","忘记打卡");
        mvc.perform(post("/api/approvals/preview").with(user("employee")).with(csrf()).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isBadRequest());
        jdbc.update("INSERT INTO employee_workplace(user_id,workplace_id) VALUES(?,?)",employee,place);
        mvc.perform(post("/api/approvals/preview").with(user("employee")).with(csrf()).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isOk());
    }
    @Test void unassignedAndDisabledCompanyApplicantsAreRejected() throws Exception {
        jdbc.update("UPDATE sys_user SET company_id=NULL WHERE username='employee'");
        mvc.perform(post("/api/approvals/preview").with(user("employee")).with(csrf()).contentType("application/json").content(json.writeValueAsString(draft()))).andExpect(status().isBadRequest());
    }
    @Test void disabledCompanyCannotPreview() throws Exception {
        jdbc.update("UPDATE org_company SET enabled=FALSE WHERE id=(SELECT company_id FROM sys_user WHERE username='employee')");
        mvc.perform(post("/api/approvals/preview").with(user("employee")).with(csrf()).contentType("application/json").content(json.writeValueAsString(draft()))).andExpect(status().isConflict());
    }
    @Test void realStorageRequiresValidInputAndManagerPermission() throws Exception {
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM approval_request WHERE applicant_id=(SELECT id FROM sys_user WHERE username='employee')",Long.class);
        mvc.perform(post("/api/approvals").with(user("employee")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/approvals/me").with(user("employee"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(total));
        mvc.perform(get("/api/approvals/inbox").with(user("employee"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/approvals/inbox").with(user("hr").roles("HR"))).andExpect(status().isOk());
        mvc.perform(get("/api/approvals/me").with(user("employee")).param("size","101")).andExpect(status().isBadRequest());
    }
}
