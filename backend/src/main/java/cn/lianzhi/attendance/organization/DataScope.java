package cn.lianzhi.attendance.organization;

import cn.lianzhi.attendance.common.BusinessException;
import cn.lianzhi.attendance.identity.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import java.util.Objects;

/** B/C复用：调用者必须来自服务端认证上下文，不能由请求体指定。 */
@Service
public class DataScope {
    private final AccountMapper accounts;
    public DataScope(AccountMapper accounts) { this.accounts = accounts; }
    public UserAccount actor(Authentication authentication) {
        UserAccount actor = authentication == null ? null : accounts.findByUsername(authentication.getName());
        if (actor == null || !actor.enabled()) throw BusinessException.forbidden();
        return actor;
    }
    public void manager(Authentication authentication) {
        if (!java.util.Set.of("ADMIN", "HR").contains(actor(authentication).role())) throw BusinessException.forbidden();
    }
    public void admin(Authentication authentication) {
        if (!actor(authentication).role().equals("ADMIN")) throw BusinessException.forbidden();
    }
    public Long companyFilter(Authentication authentication, Long requested) {
        UserAccount actor = actor(authentication);
        if (actor.role().equals("ADMIN")) return requested;
        if (actor.companyId() == null || (requested != null && !requested.equals(actor.companyId()))) throw BusinessException.forbidden();
        return actor.companyId();
    }
    public void company(Authentication authentication, Long companyId) {
        if (!actor(authentication).role().equals("ADMIN") && !Objects.equals(companyFilter(authentication, companyId), companyId)) throw BusinessException.forbidden();
    }
    public void employee(Authentication authentication, Long userId, Long companyId) {
        UserAccount actor = actor(authentication);
        if (actor.id().equals(userId)) return;
        if (actor.role().equals("EMPLOYEE")) {
            if (!actor.id().equals(userId)) throw BusinessException.forbidden();
        } else company(authentication, companyId);
    }
}
