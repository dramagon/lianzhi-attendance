package cn.lianzhi.attendance.web;

import cn.lianzhi.attendance.common.ApiResponse;
import cn.lianzhi.attendance.identity.PasswordService;
import cn.lianzhi.attendance.identity.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profile")
public class PasswordController {
    private final PasswordService passwords;
    public PasswordController(PasswordService passwords) { this.passwords = passwords; }

    public record PasswordInput(String currentPassword, String newPassword, String confirmPassword) {}

    @PostMapping("/password")
    public ApiResponse<Void> change(Authentication authentication, @RequestBody PasswordInput input,
                                    HttpServletRequest request, HttpServletResponse response) {
        if (!(authentication.getPrincipal() instanceof SessionUser user))
            throw new AccessDeniedException("请重新登录后修改密码");
        passwords.change(user.getUsername(), user.version(), input.currentPassword(), input.newPassword(), input.confirmPassword());
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        return ApiResponse.ok(null);
    }
}
