package cn.lianzhi.attendance;

import cn.lianzhi.attendance.identity.*;
import cn.lianzhi.attendance.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="lianzhi.avatar-directory=./target/test-avatars")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AvatarIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired AvatarStorage storage;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @TempDir java.nio.file.Path temporaryDirectory;
    @BeforeEach @AfterEach void cleanup() {
        for (String username:List.of("admin","hr","employee")) storage.remove(accounts.findByUsername(username).id());
    }
    private MockMultipartFile picture(int width,int height) throws IOException {
        var output=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB),"png",output);
        return new MockMultipartFile("file","avatar.png","image/png",output.toByteArray());
    }
    @Test void everyRoleCanUploadOwnAvatarAndRemoveItWithoutLosingSession() throws Exception {
        for (String username:List.of("admin","hr","employee")) {
            Long id=accounts.findByUsername(username).id();
            var response=mvc.perform(multipart("/api/profile/avatar").file(picture(48,32)).with(user(username)).with(csrf()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.passwordHash").doesNotExist()).andReturn();
            String url=json.readTree(response.getResponse().getContentAsString()).path("data").path("avatarUrl").asText();
            assertThat(url).startsWith("/api/profile/avatars/"+id+"?v=");
            byte[] bytes=mvc.perform(get("/api/profile/avatars/"+id).with(user(username))).andExpect(status().isOk())
                    .andExpect(content().contentType("image/jpeg")).andReturn().getResponse().getContentAsByteArray();
            var decoded=ImageIO.read(new ByteArrayInputStream(bytes));
            assertThat(decoded.getWidth()).isEqualTo(320); assertThat(decoded.getHeight()).isEqualTo(320);
            mvc.perform(get("/api/profile").with(user(username))).andExpect(status().isOk()).andExpect(jsonPath("$.data.avatarUrl").value(url));
            mvc.perform(delete("/api/profile/avatar").with(user(username)).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.data.avatarUrl").isEmpty());
            mvc.perform(get("/api/profile/avatars/"+id).with(user(username))).andExpect(status().isNotFound());
        }
    }
    @Test void anonymousCsrfAndOtherEmployeeScopeAreEnforced() throws Exception {
        Long hrId=accounts.findByUsername("hr").id();
        mvc.perform(multipart("/api/profile/avatar").file(picture(32,32)).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/profile/avatar").file(picture(32,32)).with(user("employee"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/profile/avatars/"+hrId).with(user("employee"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/profile/avatar").with(user("employee"))).andExpect(status().isForbidden());
    }
    @Test void corruptOversizedSvgAndOversizedDimensionsAreRejected() throws Exception {
        for (var invalid:List.of(
                new MockMultipartFile("file","fake.png","image/png","not an image".getBytes()),
                new MockMultipartFile("file","avatar.svg","image/svg+xml","<svg/>".getBytes()),
                new MockMultipartFile("file","large.png","image/png",new byte[2*1024*1024+1]),
                picture(4097,1))) {
            mvc.perform(multipart("/api/profile/avatar").file(invalid).with(user("employee")).with(csrf())).andExpect(status().isBadRequest());
        }
    }
    @Test void clientCannotChooseWhichAccountIsModified() throws Exception {
        Long self=accounts.findByUsername("employee").id(),other=accounts.findByUsername("hr").id();
        mvc.perform(multipart("/api/profile/avatar").file(picture(32,32)).param("userId",other.toString()).with(user("employee")).with(csrf())).andExpect(status().isOk());
        assertThat(storage.url(self)).isNotNull(); assertThat(storage.url(other)).isNull();
    }
    @Test @Transactional void unassignedHrCanMaintainOwnAvatarButCannotReadOtherEmployees() throws Exception {
        jdbc.update("UPDATE sys_user SET company_id=NULL WHERE username='hr'");
        Long hrId=accounts.findByUsername("hr").id(),employeeId=accounts.findByUsername("employee").id();
        mvc.perform(get("/api/employees/me").with(user("hr"))).andExpect(status().isOk());
        mvc.perform(multipart("/api/profile/avatar").file(picture(32,32)).with(user("hr")).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/profile/avatars/"+hrId).with(user("hr"))).andExpect(status().isOk());
        mvc.perform(get("/api/employees/"+employeeId).with(user("hr"))).andExpect(status().isForbidden());
    }
    @Test void storageSurvivesRecreationAndIsolatesDatabases() throws Exception {
        var first=new AvatarStorage(temporaryDirectory.toString(),"jdbc:h2:mem:first");
        first.save(1L,picture(32,32));
        assertThat(new AvatarStorage(temporaryDirectory.toString(),"jdbc:h2:mem:first").read(1L)).isEqualTo(first.read(1L));
        assertThat(new AvatarStorage(temporaryDirectory.toString(),"jdbc:h2:mem:second").url(1L)).isNull();
    }
}
