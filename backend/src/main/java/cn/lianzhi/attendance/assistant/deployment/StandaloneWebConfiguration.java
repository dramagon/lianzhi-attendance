package cn.lianzhi.attendance.assistant.deployment;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.*;

/** Optional same-origin distribution; API authentication still uses the original security chain. */
@Configuration @Profile("standalone & !classroom")
public class StandaloneWebConfiguration implements WebMvcConfigurer {
    @Bean @Order(0)
    SecurityFilterChain standaloneStatic(HttpSecurity http)throws Exception{
        return http.securityMatcher(request->{String p=request.getRequestURI().substring(request.getContextPath().length());return !p.equals("/api")&&!p.startsWith("/api/");})
            .authorizeHttpRequests(auth->auth.anyRequest().permitAll())
            .headers(headers->headers.contentSecurityPolicy(csp->csp.policyDirectives("default-src 'self'; script-src 'self' https://webapi.amap.com https://webapi.amap.com.cn; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https://*.amap.com https://*.autonavi.com; media-src 'self' blob:; connect-src 'self' https://*.amap.com https://*.amap.com.cn https://*.autonavi.com; worker-src 'self' blob:; frame-ancestors 'none'")))
            .build();
    }
    @Override public void addViewControllers(ViewControllerRegistry registry){for(String path:new String[]{"/","/{page:(?!api$)[^.]+}","/approvals/**","/attendance/**"})registry.addViewController(path).setViewName("forward:/index.html");}
}
