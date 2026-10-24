package cn.lianzhi.attendance.config;

import cn.lianzhi.attendance.common.ApiResponse;
import cn.lianzhi.attendance.identity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    UserDetailsService userDetailsService(AccountMapper accounts) {
        return username -> {
            UserAccount account = accounts.findByUsername(username);
            if (account == null) throw new UsernameNotFoundException("账号或密码错误");
            return User.withUsername(account.username()).password(account.passwordHash())
                    .roles(account.role()).disabled(!Boolean.TRUE.equals(account.enabled())).build();
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json, AccountService accounts) throws Exception {
        return http
                .csrf(Customizer.withDefaults())
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/csrf", "/api/system/info", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(login -> login.loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) -> write(json, response, 200,
                                ApiResponse.ok(accounts.current(authentication.getName()))))
                        .failureHandler((request, response, exception) -> write(json, response, 401,
                                ApiResponse.error("INVALID_CREDENTIALS", "账号或密码错误，请重新输入")))
                        .permitAll())
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> write(json, response, 200, ApiResponse.ok(null))))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> write(json, response, 401,
                                ApiResponse.error("UNAUTHENTICATED", "登录已失效，请重新登录")))
                        .accessDeniedHandler((request, response, exception) -> write(json, response, 403,
                                ApiResponse.error("FORBIDDEN", "无权执行此操作，或安全令牌已失效，请刷新后重试"))))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .build();
    }

    private static void write(ObjectMapper json, HttpServletResponse response, int status, Object body) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getWriter(), body);
    }
}
