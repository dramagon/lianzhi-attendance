package cn.lianzhi.attendance.infrastructure.session;

import cn.lianzhi.attendance.common.ApiResponse;
import cn.lianzhi.attendance.assistant.SharedConfirmationGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.FlushMode;
import org.springframework.session.SaveMode;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "lianzhi.session.redis", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RedisSessionSettings.class)
@EnableSpringHttpSession
public class RedisSessionConfiguration {
    @Bean
    LettuceConnectionFactory sessionRedisConnectionFactory(RedisSessionSettings settings) {
        var server = new RedisStandaloneConfiguration(settings.host(), settings.port());
        server.setDatabase(settings.database());
        if (!settings.username().isEmpty()) server.setUsername(settings.username());
        if (!settings.password().isEmpty()) server.setPassword(RedisPassword.of(settings.password()));
        var options = ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(settings.connectTimeout()).build())
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build();
        var client = LettuceClientConfiguration.builder().clientOptions(options)
                .commandTimeout(settings.commandTimeout()).shutdownTimeout(Duration.ofMillis(100));
        if (settings.tls()) client.useSsl();
        return new LettuceConnectionFactory(server, client.build());
    }

    @Bean
    RedisTemplate<String, Object> sessionRedisTemplate(LettuceConnectionFactory factory) {
        var template = new RedisTemplate<String, Object>();
        template.setConnectionFactory(factory);
        var keys = new StringRedisSerializer();
        var values = new JdkSerializationRedisSerializer();
        template.setKeySerializer(keys); template.setHashKeySerializer(keys);
        template.setValueSerializer(values); template.setHashValueSerializer(values);
        return template;
    }

    @Bean
    StringRedisTemplate confirmationRedisTemplate(LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    SharedConfirmationGuard sharedConfirmationGuard(StringRedisTemplate confirmationRedisTemplate, RedisSessionSettings settings) {
        return new RedisConfirmationGuard(confirmationRedisTemplate, settings.namespace());
    }

    @Bean
    SessionRepository<? extends Session> sessionRepository(RedisTemplate<String, Object> sessionRedisTemplate,
            LettuceConnectionFactory factory, RedisSessionSettings settings, ServerProperties server) {
        try (RedisConnection connection = factory.getConnection()) {
            if (!"PONG".equals(connection.ping())) throw new IllegalStateException("Redis health check failed");
        } catch (RuntimeException error) {
            throw new IllegalStateException("Configured Redis session store is unavailable; startup aborted");
        }
        var timeout = server.getServlet().getSession().getTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("Session timeout must be positive");
        var repository = new RedisSessionRepository(sessionRedisTemplate);
        repository.setRedisKeyNamespace(settings.namespace());
        repository.setDefaultMaxInactiveInterval(timeout);
        repository.setFlushMode(FlushMode.ON_SAVE);
        repository.setSaveMode(SaveMode.ON_SET_ATTRIBUTE);
        return new GuardedSessionRepository<>(repository);
    }

    @Bean
    CookieSerializer sessionCookieSerializer(ServerProperties server) {
        var cookie = new DefaultCookieSerializer();
        cookie.setCookieName("JSESSIONID"); cookie.setCookiePath("/");
        cookie.setUseBase64Encoding(false); cookie.setUseHttpOnlyCookie(true);
        cookie.setSameSite("Lax");
        cookie.setUseSecureCookie(Boolean.TRUE.equals(server.getServlet().getSession().getCookie().getSecure()));
        return cookie;
    }

    @Bean
    FilterRegistrationBean<Filter> sessionFilterRegistration(
            @Qualifier("springSessionRepositoryFilter") Filter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return registration;
    }

    @Bean
    FilterRegistrationBean<Filter> sessionFailureFilter(ObjectMapper json) {
        Filter filter = new OncePerRequestFilter() {
            @Override protected boolean shouldNotFilterErrorDispatch() { return false; }
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws IOException, ServletException {
                try { chain.doFilter(request, response); }
                catch (SessionStoreUnavailable error) {
                    SecurityContextHolder.clearContext();
                    if (response.isCommitted()) throw error;
                    response.resetBuffer(); response.setStatus(503);
                    response.setContentType("application/json;charset=UTF-8");
                    response.setHeader("Cache-Control", "no-store");
                    json.writeValue(response.getWriter(), ApiResponse.error("SESSION_STORE_UNAVAILABLE", "会话服务暂不可用，请稍后重试"));
                }
            }
        };
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER - 1);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return registration;
    }
}
