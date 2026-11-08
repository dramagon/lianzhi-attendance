package cn.lianzhi.attendance.infrastructure.session;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lianzhi.session.redis")
public record RedisSessionSettings(boolean enabled, String host, int port, int database,
        String username, String password, boolean tls, String namespace,
        Duration connectTimeout, Duration commandTimeout) {
    public RedisSessionSettings {
        if (host == null || !host.matches("[A-Za-z0-9._:-]{1,253}") || port < 1 || port > 65535 || database < 0)
            throw new IllegalArgumentException("Redis host, port or database is invalid");
        if (namespace == null || !namespace.matches("[A-Za-z0-9:_-]{1,100}"))
            throw new IllegalArgumentException("Redis session namespace is invalid");
        for (Duration timeout : new Duration[]{connectTimeout, commandTimeout})
            if (timeout == null || timeout.compareTo(Duration.ofMillis(100)) < 0 || timeout.compareTo(Duration.ofSeconds(10)) > 0)
                throw new IllegalArgumentException("Redis timeouts must be between 100ms and 10s");
        username = username == null ? "" : username;
        password = password == null ? "" : password;
    }
    @Override public String toString() { return "RedisSessionSettings[enabled=" + enabled + ", credentials=REDACTED]"; }
}
