package cn.lianzhi.attendance.assistant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles({"test","standalone"})
class StandaloneWebTest {
    @Autowired MockMvc mvc;
    @Test void publicSpaDoesNotOpenApiOrCsrf()throws Exception{
        mvc.perform(get("/reports")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/api/knowledge/documents")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reports/attendance?from=2026-01-01&to=2026-01-01")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/assistant/approval-drafts")).andExpect(status().isForbidden());
    }
}
