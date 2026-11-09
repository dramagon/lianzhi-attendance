package cn.lianzhi.attendance.identity;

import cn.lianzhi.attendance.common.BusinessException;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordService {
    private final AccountMapper accounts;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    public PasswordService(AccountMapper accounts, JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    @Transactional
    public void change(String username, long version, String currentPassword, String newPassword, String confirmation) {
        if (currentPassword == null || currentPassword.isEmpty() || currentPassword.getBytes(StandardCharsets.UTF_8).length > 72)
            throw BusinessException.invalid("请填写当前密码");
        if (newPassword == null || newPassword.length() < 4 || newPassword.getBytes(StandardCharsets.UTF_8).length > 72)
            throw BusinessException.invalid("新密码至少4位，UTF-8编码不超过72字节");
        if (!newPassword.equals(confirmation)) throw BusinessException.invalid("两次新密码输入不一致");
        jdbc.queryForObject("SELECT id FROM sys_user WHERE username=? FOR UPDATE", Long.class, username);
        var account = accounts.findByUsername(username);
        if (account == null || !account.enabled() || account.authVersion() != version)
            throw new AccessDeniedException("账号已变更，请重新登录");
        if (!passwords.matches(currentPassword, account.passwordHash()))
            throw BusinessException.invalid("当前密码不正确");
        if (passwords.matches(newPassword, account.passwordHash()))
            throw BusinessException.invalid("新密码不能与当前密码相同");
        int changed = jdbc.update("UPDATE sys_user SET password_hash=?,auth_version=auth_version+1 WHERE id=? AND auth_version=?",
                passwords.encode(newPassword), account.id(), version);
        if (changed != 1) throw new AccessDeniedException("账号已变更，请重新登录");
    }
}
