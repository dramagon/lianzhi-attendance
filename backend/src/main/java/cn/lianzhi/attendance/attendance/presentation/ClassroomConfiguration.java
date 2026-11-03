package cn.lianzhi.attendance.attendance.presentation;

import cn.lianzhi.attendance.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.*;

/** Opt-in, loopback classroom package. The normal authenticated API chain is unchanged. */
@Configuration
@Profile("classroom")
@ConditionalOnProperty(name="lianzhi.demo",havingValue="true")
public class ClassroomConfiguration implements WebMvcConfigurer {
    @Bean @Order(0)
    SecurityFilterChain classroomStaticFiles(HttpSecurity http) throws Exception {
        return http.securityMatcher(request -> {
                String path=request.getRequestURI().substring(request.getContextPath().length());
                return !path.equals("/api") && !path.startsWith("/api/");
            })
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'")))
            .build();
    }
    @Override public void addViewControllers(ViewControllerRegistry registry) {
        for(String path:new String[]{"/","/login","/workbench","/profile","/accounts","/roadmap","/forbidden",
            "/organization","/approvals","/approvals/**","/attendance","/attendance/**","/{page:(?!api$)[^.]+}"}) {
            registry.addViewController(path).setViewName("forward:/index.html");
        }
    }
    @RestController
    @Profile("classroom")
    @ConditionalOnProperty(name="lianzhi.demo",havingValue="true")
    static class ClassroomInfo {
        private final String instance=java.util.UUID.randomUUID().toString();
        @GetMapping("/api/attendance/presentation")
        ApiResponse<Info> info() { return ApiResponse.ok(new Info(true,instance,"课堂演示：合成员工、演示定位；不代表真实人脸识别")); }
        record Info(boolean classroom,String instance,String message) {}
    }
}
