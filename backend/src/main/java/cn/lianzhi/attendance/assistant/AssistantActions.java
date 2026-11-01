package cn.lianzhi.attendance.assistant;

import cn.lianzhi.attendance.approval.*;
import cn.lianzhi.attendance.common.BusinessException;
import cn.lianzhi.attendance.identity.UserAccount;
import cn.lianzhi.attendance.organization.DataScope;
import cn.lianzhi.attendance.knowledge.retrieval.KnowledgeIdentity;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** Server-owned, bounded confirmation state in the authenticated HTTP session.
 * No model/GET operation writes an application. Only confirm calls A's transactional service. */
@Service
public class AssistantActions {
    private static final String ATTRIBUTE=AssistantActions.class.getName()+".pending";
    private static final int LIMIT=10;
    private final ApprovalService approvals;
    private final DataScope scope;
    private final KnowledgeIdentity identity;
    private final Clock clock;
    private final TransactionTemplate transactions;
    @Autowired public AssistantActions(ApprovalService approvals,DataScope scope,KnowledgeIdentity identity,PlatformTransactionManager manager){this(approvals,scope,identity,manager,Clock.systemUTC());}
    AssistantActions(ApprovalService approvals,DataScope scope,KnowledgeIdentity identity,PlatformTransactionManager manager,Clock clock){
        this.approvals=approvals;this.scope=scope;this.identity=identity;this.clock=clock;
        transactions=new TransactionTemplate(manager);transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public record Prepare(String intentKey,ApprovalRules.Draft draft) {}
    public record Confirmation(String confirmationId,Instant expiresAt,ApprovalService.Preview preview) {}
    public record Confirm(String confirmationId,Boolean confirmed) {}
    private static final class Pending {
        final String intentKey,id;final long userId,authVersion;final Long companyId;final String role;final Instant expiresAt;final ApprovalService.Preview preview;
        boolean cancelled,submitted;
        Pending(String key,UserAccount actor,ApprovalService.Preview preview,Instant expiry){
            intentKey=key;id=UUID.randomUUID().toString();userId=actor.id();authVersion=actor.authVersion();companyId=actor.companyId();role=actor.role();expiresAt=expiry;this.preview=preview;
        }
        Confirmation view(){return new Confirmation(id,expiresAt,preview);}
    }
    private static final class SessionState { final Map<String,Pending> items=new LinkedHashMap<>(); }
    private SessionState state(HttpSession session){
        if(session==null)throw BusinessException.forbidden();
        synchronized(session){
            var existing=session.getAttribute(ATTRIBUTE);
            if(existing instanceof SessionState s)return s;
            var created=new SessionState();session.setAttribute(ATTRIBUTE,created);return created;
        }
    }
    public Confirmation prepare(Authentication actor,HttpSession session,Prepare input){
        identity.current(actor);approvals.requireStorage();
        if(input==null || input.intentKey()==null)throw BusinessException.invalid("缺少申请意图编号");
        try{if(!UUID.fromString(input.intentKey()).toString().equals(input.intentKey()))throw new IllegalArgumentException();}
        catch(IllegalArgumentException e){throw BusinessException.invalid("申请意图编号须为标准UUID");}
        // A checks type/time/company/workplace and returns a normalized, immutable draft. No insert.
        var preview=approvals.preview(actor,input.draft());var account=scope.actor(actor);var state=state(session);
        if(!Objects.equals(preview.companyId(),account.companyId()))throw BusinessException.conflict("组织归属已改变，请重新准备");
        synchronized(state){
            state.items.values().removeIf(p->!clock.instant().isBefore(p.expiresAt));
            for(var p:state.items.values())if(p.intentKey.equals(input.intentKey())){
                unchanged(p,account);
                if(p.cancelled || p.submitted || !p.preview.draft().equals(preview.draft()))throw BusinessException.conflict("此意图已使用或内容已变化，请重新准备");
                return p.view();
            }
            if(state.items.size()>=LIMIT)throw BusinessException.conflict("待确认草稿过多，请稍后重试");
            var pending=new Pending(input.intentKey(),account,preview,clock.instant().plus(Duration.ofMinutes(10)));
            state.items.put(pending.id,pending);return pending.view();
        }
    }
    public ApprovalStore.Entry confirm(Authentication actor,HttpSession session,Confirm input){
        identity.current(actor);
        if(input==null || !Boolean.TRUE.equals(input.confirmed()))throw BusinessException.invalid("请明确确认申请内容后再提交");
        var state=state(session);
        synchronized(state){
            var pending=required(state,input.confirmationId());unchanged(pending,scope.actor(actor));
            if(pending.cancelled)throw BusinessException.conflict("草稿已取消，请重新准备");
            if(!clock.instant().isBefore(pending.expiresAt))throw BusinessException.conflict("确认已过期，请先核对已有申请再重新准备");
            // Recheck current company/workplace/date constraints even when A returns an idempotent replay.
            var result=transactions.execute(status->{
                unchanged(pending,scope.actor(actor));
                approvals.preview(actor,pending.preview.draft());
                var created=approvals.create(actor,new ApprovalService.Submission(pending.intentKey,pending.preview.draft()));
                // A's create locks the employee until this transaction completes. If identity changed
                // before that lock was obtained, reject here and roll back the insertion and audit.
                unchanged(pending,scope.actor(actor));identity.current(actor);return created;
            });
            // A's transaction has committed before this flag is set. If the response is lost,
            // retrying the same confirmation/intent uses A's database idempotency, not a fake result.
            pending.submitted=true;return result;
        }
    }
    public void cancel(Authentication actor,HttpSession session,String id){
        identity.current(actor);var state=state(session);
        synchronized(state){
            var pending=required(state,id);unchanged(pending,scope.actor(actor));
            if(pending.submitted)throw BusinessException.conflict("申请已提交，请在我的申请中查看或撤回");
            pending.cancelled=true;
        }
    }
    private Pending required(SessionState state,String id){var p=state.items.get(id);if(p==null)throw BusinessException.missing();return p;}
    private void unchanged(Pending p,UserAccount actor){
        if(p.userId!=actor.id() || p.authVersion!=actor.authVersion() || !Objects.equals(p.companyId,actor.companyId()) || !p.role.equals(actor.role()))
            throw BusinessException.forbidden();
    }
}
