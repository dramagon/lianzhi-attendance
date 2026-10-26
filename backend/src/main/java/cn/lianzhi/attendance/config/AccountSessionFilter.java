package cn.lianzhi.attendance.config;

import cn.lianzhi.attendance.common.ApiResponse;
import cn.lianzhi.attendance.identity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** 账号停用、角色/公司/密码改变后，下次请求即撤销旧会话。 */
public class AccountSessionFilter extends OncePerRequestFilter {
    private final AccountMapper accounts;
    private final ObjectMapper json;
    public AccountSessionFilter(AccountMapper accounts,ObjectMapper json) { this.accounts=accounts; this.json=json; }
    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if (authentication!=null && authentication.getPrincipal() instanceof SessionUser principal) {
            var current=accounts.findByUsername(principal.getUsername());
            if (current==null || !current.enabled() || current.authVersion()!=principal.version()) {
                SecurityContextHolder.clearContext();
                var session=request.getSession(false); if (session!=null) session.invalidate();
                response.setStatus(401); response.setContentType("application/json;charset=UTF-8");
                json.writeValue(response.getWriter(),ApiResponse.error("SESSION_REVOKED","账号资料或权限已变更，请重新登录"));
                return;
            }
        }
        chain.doFilter(request,response);
    }
}
