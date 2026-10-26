package cn.lianzhi.attendance.identity;

import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AccountService {
    private final AccountMapper accounts;
    private final AvatarStorage avatars;

    public AccountService(AccountMapper accounts,AvatarStorage avatars) { this.accounts = accounts; this.avatars=avatars; }

    public UserView view(UserAccount account) { return UserView.from(account).withAvatar(avatars.url(account.id())); }

    public UserView current(String username) {
        UserAccount account = accounts.findByUsername(username);
        if (account == null) throw new UsernameNotFoundException("账号不存在");
        if (!Boolean.TRUE.equals(account.enabled())) throw new DisabledException("账号不可用");
        return view(account);
    }
}
