package cn.lianzhi.attendance.infrastructure.session;

import cn.lianzhi.attendance.identity.SessionUser;
import cn.lianzhi.attendance.identity.UserAccount;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import static org.assertj.core.api.Assertions.*;

class RedisSessionConfigurationTest {
    @Test void dependencyDoesNotActivateRedisWithoutTheExplicitSwitch() {
        new ApplicationContextRunner().withUserConfiguration(RedisSessionConfiguration.class)
                .run(context -> { assertThat(context).doesNotHaveBean(SessionRepository.class);
                    assertThat(context).doesNotHaveBean(RedisConnectionFactory.class); });
    }
    @Test void erasedPrincipalRoundTripRetainsAuthorizationVersionWithoutPasswordHash() {
        var account = new UserAccount(42L,"synthetic","SHOULD_NOT_BE_CACHED","合成用户","HR","TEST",null,null,
                true,LocalDateTime.now(),1L,7L);
        var principal = new SessionUser(account); principal.eraseCredentials();
        var serializer = new JdkSerializationRedisSerializer();
        byte[] bytes = serializer.serialize(principal);
        assertThat(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("SHOULD_NOT_BE_CACHED",UserAccount.class.getName());
        var restored = (SessionUser) serializer.deserialize(bytes);
        assertThat(restored.version()).isEqualTo(7); assertThat(restored.getUsername()).isEqualTo("synthetic");
        assertThat(restored.getPassword()).isNull();
        assertThat(restored.getAuthorities()).extracting("authority").containsExactly("ROLE_HR");
    }
    @Test void redisCookieRetainsExistingNameAndSecurityAttributes() {
        var server = new ServerProperties(); server.getServlet().getSession().getCookie().setSecure(true);
        var serializer = new RedisSessionConfiguration().sessionCookieSerializer(server);
        var response = new MockHttpServletResponse();
        serializer.writeCookieValue(new CookieSerializer.CookieValue(new MockHttpServletRequest(),response,"synthetic-session"));
        assertThat(response.getHeader("Set-Cookie")).contains("JSESSIONID=synthetic-session","Path=/","HttpOnly","Secure","SameSite=Lax");
    }
    @Test void settingsRejectInvalidBoundariesAndRedactCredentials() {
        var settings = new RedisSessionSettings(true,"localhost",6379,0,"user","SECRET",false,"lianzhi:session:v1",Duration.ofSeconds(1),Duration.ofSeconds(1));
        assertThat(settings.toString()).doesNotContain("SECRET","user");
        assertThatThrownBy(() -> new RedisSessionSettings(true,"localhost",0,0,"","",false,"test",Duration.ofSeconds(1),Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisSessionSettings(true,"localhost",6379,0,"","",false,"test*",Duration.ofSeconds(1),Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisSessionSettings(true,"localhost",6379,0,"","",false,"test",Duration.ZERO,Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
