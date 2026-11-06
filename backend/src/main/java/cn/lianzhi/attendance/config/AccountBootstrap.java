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
        if (accounts.count() != 0) {
            if (demo) normalizeDefaultNames();
            return;
        }
        if (demo) {
            jdbc.update("INSERT INTO org_company(name) VALUES (?)", "公司一");
            Long companyId = jdbc.queryForObject("SELECT id FROM org_company WHERE name='公司一'",Long.class);
            jdbc.update("INSERT INTO sys_department(name,company_id) VALUES (?,?)", "部门一",companyId);
            Long departmentId = jdbc.queryForObject("SELECT MIN(id) FROM sys_department", Long.class);
            String hash = passwords.encode("Lianzhi@2026");
            accounts.insert("admin", hash, "管理员一", "ADMIN", "DEMO001", departmentId);
            accounts.insert("hr", hash, "人事一", "HR", "DEMO002", departmentId);
            accounts.insert("employee", hash, "员工一", "EMPLOYEE", "DEMO003", departmentId);
            jdbc.update("UPDATE sys_user SET company_id=? WHERE department_id=?",companyId,departmentId);
        } else {
            if (initialPassword.length() < 4) {
                throw new IllegalStateException("首次启动非演示环境必须设置至少4位的 ADMIN_INITIAL_PASSWORD");
            }
            accounts.insert("admin", passwords.encode(initialPassword), "系统管理员", "ADMIN", "ADMIN001", null);
        }
    }

    private void normalizeDefaultNames() {
        jdbc.update("UPDATE sys_user SET display_name=? WHERE username=? AND display_name=?", "管理员一", "admin", "演示管理员");
        jdbc.update("UPDATE sys_user SET display_name=? WHERE username=? AND display_name=?", "人事一", "hr", "演示人事");
        jdbc.update("UPDATE sys_user SET display_name=? WHERE username=? AND display_name=?", "员工一", "employee", "演示员工");
        jdbc.update("UPDATE sys_user SET display_name=CONCAT('员工',id) WHERE display_name='课堂合成员工' AND username REGEXP '^show[0-9]+$'");
        jdbc.update("UPDATE workplace SET name=CONCAT('办公场所',id) WHERE name REGEXP '^课堂演示[0-9]+$'");
        jdbc.update("UPDATE org_company SET name='公司一' WHERE name='演示公司' AND NOT EXISTS (SELECT 1 FROM (SELECT name FROM org_company) existing_names WHERE existing_names.name='公司一')");
        jdbc.update("UPDATE sys_department SET name='部门一' WHERE name='演示部门' AND NOT EXISTS (SELECT 1 FROM (SELECT name FROM sys_department) existing_names WHERE existing_names.name='部门一')");
    }
}
