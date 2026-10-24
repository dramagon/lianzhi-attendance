package cn.lianzhi.attendance.config;

import cn.lianzhi.attendance.identity.AccountMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AccountBootstrap implements ApplicationRunner {
    private final AccountMapper accounts;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final boolean demo;
    private final String initialPassword;

    public AccountBootstrap(AccountMapper accounts, JdbcTemplate jdbc, PasswordEncoder passwords,
                            @Value("${lianzhi.demo:false}") boolean demo,
                            @Value("${ADMIN_INITIAL_PASSWORD:}") String initialPassword) {
        this.accounts = accounts; this.jdbc = jdbc; this.passwords = passwords;
        this.demo = demo; this.initialPassword = initialPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (accounts.count() != 0) return;
        if (demo) {
            jdbc.update("INSERT INTO sys_department(name) VALUES (?)", "演示部门");
            Long departmentId = jdbc.queryForObject("SELECT MIN(id) FROM sys_department", Long.class);
            String hash = passwords.encode("Lianzhi@2026");
            accounts.insert("admin", hash, "演示管理员", "ADMIN", "DEMO001", departmentId);
            accounts.insert("hr", hash, "演示人事", "HR", "DEMO002", departmentId);
            accounts.insert("employee", hash, "演示员工", "EMPLOYEE", "DEMO003", departmentId);
        } else {
            if (initialPassword.length() < 12) {
                throw new IllegalStateException("首次启动非演示环境必须设置至少12位的 ADMIN_INITIAL_PASSWORD");
            }
            accounts.insert("admin", passwords.encode(initialPassword), "系统管理员", "ADMIN", "ADMIN001", null);
        }
    }
}
