package cn.lianzhi.attendance.identity;

import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;

public class SessionUser extends User {
    private final long version;
    public SessionUser(UserAccount account) {
        super(account.username(),account.passwordHash(),account.enabled(),true,true,true,AuthorityUtils.createAuthorityList("ROLE_"+account.role()));
        this.version=account.authVersion();
    }
    public long version() { return version; }
}
