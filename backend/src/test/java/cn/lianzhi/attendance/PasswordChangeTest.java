package cn.lianzhi.attendance;

import cn.lianzhi.attendance.identity.PasswordService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class PasswordChangeTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired PasswordService passwords;
    final List<String> users = new ArrayList<>();

    String create(String role) {
        String username="pw-"+UUID.randomUUID().toString().substring(0,20);
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,role,employee_no) VALUES(?,?,?,?,?)",username,encoder.encode("old1"),"密码验证",role,username);
        users.add(username);
        return username;
    }
    @AfterEach void cleanup() { for(String username:users) jdbc.update("DELETE FROM sys_user WHERE username=?",username); }
    MockHttpSession login(String username,String password) throws Exception {
        var response=mvc.perform(post("/api/auth/login").with(csrf()).param("username",username).param("password",password)).andExpect(status().isOk()).andReturn();
        return (MockHttpSession)response.getRequest().getSession();
    }
    String body(String old,String next,String confirmation) throws Exception {
        return json.writeValueAsString(Map.of("currentPassword",old,"newPassword",next,"confirmPassword",confirmation));
    }
    @Test void allRolesChangePasswordAndRevokeAllOldSessions() throws Exception {
        for(String role:List.of("ADMIN","HR","EMPLOYEE")) {
            String username=create(role);
            var first=login(username,"old1");var other=login(username,"old1");
            mvc.perform(post("/api/profile/password").session(first).with(csrf()).contentType("application/json").content(body("old1","new2","new2"))).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
            assertThat(first.isInvalid()).isTrue();
            mvc.perform(get("/api/auth/session").session(other)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/auth/login").with(csrf()).param("username",username).param("password","old1")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/auth/session").session(login(username,"new2"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value(username));
            assertThat(jdbc.queryForObject("SELECT auth_version FROM sys_user WHERE username=?",Long.class,username)).isEqualTo(1L);
        }
    }
    @Test void invalidInputsKeepPasswordAndSession() throws Exception {
        String username=create("EMPLOYEE");var session=login(username,"old1");
        for(var input:List.of(body("wrong","new2","new2"),body("old1","123","123"),body("old1","new2","other"),body("old1","old1","old1"),body("old1","密".repeat(25),"密".repeat(25))))
            mvc.perform(post("/api/profile/password").session(session).with(csrf()).contentType("application/json").content(input)).andExpect(status().isBadRequest());
        assertThat(session.isInvalid()).isFalse();login(username,"old1");
        assertThat(jdbc.queryForObject("SELECT auth_version FROM sys_user WHERE username=?",Long.class,username)).isZero();
    }
    @Test void csrfAndAnonymousAccessAreRejected() throws Exception {
        String username=create("EMPLOYEE");
        mvc.perform(post("/api/profile/password").session(login(username,"old1")).contentType("application/json").content(body("old1","new2","new2"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/profile/password").with(csrf()).contentType("application/json").content(body("old1","new2","new2"))).andExpect(status().isUnauthorized());
    }
    @Test void staleVersionCannotOverwriteAdministratorReset() {
        String username=create("EMPLOYEE");
        jdbc.update("UPDATE sys_user SET auth_version=auth_version+1 WHERE username=?",username);
        assertThatThrownBy(()->passwords.change(username,0,"old1","new2","new2")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(encoder.matches("old1",jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE username=?",String.class,username))).isTrue();
    }
}
