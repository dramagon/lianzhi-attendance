package cn.lianzhi.attendance;

import cn.lianzhi.attendance.identity.AccountMapper;
import cn.lianzhi.attendance.config.AccountBootstrap;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;

    @Test
    @org.springframework.transaction.annotation.Transactional
    void existingDefaultNamesAreNumberedAndCustomNamesArePreserved() {
        jdbc.update("UPDATE sys_user SET display_name='演示管理员' WHERE username='admin'");
        jdbc.update("UPDATE sys_user SET display_name='人事二' WHERE username='hr'");
        long count = accounts.count();
        new AccountBootstrap(accounts, jdbc, passwords, true, "").run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForObject("SELECT display_name FROM sys_user WHERE username='admin'", String.class)).isEqualTo("管理员一");
        assertThat(jdbc.queryForObject("SELECT display_name FROM sys_user WHERE username='hr'", String.class)).isEqualTo("人事二");
        assertThat(accounts.count()).isEqualTo(count);
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult csrf = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        String token = json.readTree(csrf.getResponse().getContentAsString()).path("data").path("token").asText();
        return (MockHttpSession) mvc.perform(post("/api/auth/login")
                .session((MockHttpSession) csrf.getRequest().getSession())
                .header("X-CSRF-TOKEN", token).param("username", username).param("password", "Lianzhi@2026"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value(username))
                .andReturn().getRequest().getSession();
    }

    @Test void anonymousCannotReadBusinessData() throws Exception {
        mvc.perform(get("/api/management/overview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/profile")).andExpect(status().isUnauthorized());
    }
    @Test void loginRequiresCsrf() throws Exception {
        mvc.perform(post("/api/auth/login").param("username", "admin").param("password", "Lianzhi@2026"))
                .andExpect(status().isForbidden());
    }
    @Test void loginRotatesSessionIdentifier() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/auth/csrf")).andReturn();
        MockHttpSession before = (MockHttpSession) csrf.getRequest().getSession();
        String oldId = before.getId();
        String token = json.readTree(csrf.getResponse().getContentAsString()).path("data").path("token").asText();
        MvcResult result = mvc.perform(post("/api/auth/login").session(before)
                .header("X-CSRF-TOKEN", token).param("username", "admin").param("password", "Lianzhi@2026"))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getRequest().getSession().getId()).isNotEqualTo(oldId);
    }
    @Test void wrongPasswordIsRejected() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/auth/csrf")).andReturn();
        String token = json.readTree(csrf.getResponse().getContentAsString()).path("data").path("token").asText();
        mvc.perform(post("/api/auth/login").session((MockHttpSession) csrf.getRequest().getSession())
                .header("X-CSRF-TOKEN", token).param("username", "admin").param("password", "wrong"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
    @Test void adminCanSeeAccountsButNoPasswordHash() throws Exception {
        MockHttpSession session = login("admin");
        // MySQL验证库可以保留页面验收账号，按真实持久化数量校验，不清空数据库。
        long total = accounts.count();
        assertThat(total).isGreaterThanOrEqualTo(3);
        mvc.perform(get("/api/admin/accounts").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(total))
                .andExpect(jsonPath("$.data[0].passwordHash").doesNotExist());
        mvc.perform(get("/api/management/overview").session(session))
                .andExpect(jsonPath("$.data.totalAccounts").value(total));
    }
    @Test void hrCannotReadAdminAccounts() throws Exception {
        MockHttpSession session = login("hr");
        mvc.perform(get("/api/management/overview").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/accounts").session(session)).andExpect(status().isForbidden());
    }
    @Test void employeeCannotBypassBackendAuthorization() throws Exception {
        MockHttpSession session = login("employee");
        mvc.perform(get("/api/employee/overview").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attendanceAvailable").value(false));
        mvc.perform(get("/api/management/overview").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/accounts").session(session)).andExpect(status().isForbidden());
    }
    @Test void logoutInvalidatesSession() throws Exception {
        MockHttpSession session = login("admin");
        MvcResult csrf = mvc.perform(get("/api/auth/csrf").session(session)).andReturn();
        String token = json.readTree(csrf.getResponse().getContentAsString()).path("data").path("token").asText();
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isOk());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/session")).andExpect(status().isUnauthorized());
    }
    @Test void passwordsAreStoredAsHashes() {
        assertThat(accounts.findByUsername("admin").passwordHash()).startsWith("$2");
        assertThat(passwords.matches("Lianzhi@2026", accounts.findByUsername("admin").passwordHash())).isTrue();
    }
    @Test void nonDemoBootstrapDoesNotCreateDefaultPassword() {
        AccountMapper empty = mock(AccountMapper.class);
        AccountBootstrap bootstrap = new AccountBootstrap(empty, mock(JdbcTemplate.class), passwords, false, "");
        assertThatThrownBy(() -> bootstrap.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
        verify(empty, never()).insert(any(), any(), any(), any(), any(), any());
    }
}
