package cn.lianzhi.attendance.knowledge;

import cn.lianzhi.attendance.knowledge.retrieval.KnowledgeWorkspace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowledgeGateIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @Test void anonymousCannotReadStatusOrDocuments() throws Exception {
        mvc.perform(get("/api/knowledge/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/knowledge/documents")).andExpect(status().isUnauthorized());
    }
    @Test void allAuthenticatedRolesSeeHonestDisabledStatus() throws Exception {
        for (String role : new String[]{"ADMIN", "HR", "EMPLOYEE"}) {
            mvc.perform(get("/api/knowledge/status").with(user("fixture").roles(role)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(false))
                    .andExpect(jsonPath("$.data.uploadAvailable").value(false))
                    .andExpect(jsonPath("$.data.textSearchAvailable").value(false))
                    .andExpect(jsonPath("$.data.vectorSearchAvailable").value(false));
        }
    }
    @Test void disabledReadsNeverReturnFakeEmptyListsOrSources() throws Exception {
        for (String path : new String[]{"/documents", "/documents/1", "/documents/1/sources/1", "/search"}) {
            mvc.perform(get("/api/knowledge" + path).with(user("fixture").roles("EMPLOYEE")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("KNOWLEDGE_UNAVAILABLE"))
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }
    @Test void writesStillRequireCsrf() throws Exception {
        mvc.perform(post("/api/knowledge/documents").with(user("fixture").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/knowledge/documents/1").with(user("fixture").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/knowledge/documents/1").with(user("fixture").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }
    @Test void csrfDoesNotEnableMissingPersistenceOrTrustForgedIdentity() throws Exception {
        mvc.perform(post("/api/knowledge/documents").with(user("fixture").roles("EMPLOYEE")).with(csrf())
                        .contentType("application/json").content("{\"userId\":1,\"companyId\":1,\"role\":\"ADMIN\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(put("/api/knowledge/documents/1").with(user("fixture").roles("ADMIN")).with(csrf()))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(delete("/api/knowledge/documents/1").with(user("fixture").roles("ADMIN")).with(csrf()))
                .andExpect(status().isServiceUnavailable());
    }
    @Test void ephemeralWorkspaceIsNotWiredIntoApplication() {
        assertThat(context.getBeansOfType(KnowledgeWorkspace.class)).isEmpty();
    }
}
