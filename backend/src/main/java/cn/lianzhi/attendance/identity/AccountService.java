package cn.lianzhi.attendance.identity;

import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AccountService {
    private final AccountMapper accounts;

    public AccountService(AccountMapper accounts) { this.accounts = accounts; }

    public UserView current(String username) {
        UserAccount account = accounts.findByUsername(username);
        if (account == null) throw new UsernameNotFoundException("账号不存在");
        if (!Boolean.TRUE.equals(account.enabled())) throw new DisabledException("账号不可用");
        return UserView.from(account);
    }
}
