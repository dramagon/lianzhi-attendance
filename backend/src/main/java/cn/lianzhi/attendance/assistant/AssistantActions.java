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
import java.io.Serial;
import java.io.Serializable;
import org.springframework.beans.factory.ObjectProvider;

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
    private final SharedConfirmationGuard shared;
    @Autowired public AssistantActions(ApprovalService approvals,DataScope scope,KnowledgeIdentity identity,PlatformTransactionManager manager,ObjectProvider<SharedConfirmationGuard> shared){this(approvals,scope,identity,manager,Clock.systemUTC(),shared.getIfAvailable());}
    public AssistantActions(ApprovalService approvals,DataScope scope,KnowledgeIdentity identity,PlatformTransactionManager manager){this(approvals,scope,identity,manager,Clock.systemUTC(),null);}
    AssistantActions(ApprovalService approvals,DataScope scope,KnowledgeIdentity identity,PlatformTransactionManager manager,Clock clock){
        this(approvals,scope,identity,manager,clock,null);
    }
    AssistantActions(ApprovalService approvals,DataScope scope,KnowledgeIdentity identity,PlatformTransactionManager manager,Clock clock,SharedConfirmationGuard shared){
        this.approvals=approvals;this.scope=scope;this.identity=identity;this.clock=clock;
        this.shared=shared;
        transactions=new TransactionTemplate(manager);transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public record Prepare(String intentKey,ApprovalRules.Draft draft) {}
    public record Confirmation(String confirmationId,Instant expiresAt,ApprovalService.Preview preview) {}
    public record Confirm(String confirmationId,Boolean confirmed) {}
    private static final class Pending implements Serializable {
        @Serial private static final long serialVersionUID=1L;
        final String intentKey,id;final long userId,authVersion;final Long companyId;final String role;final Instant expiresAt;final ApprovalService.Preview preview;
        boolean cancelled,submitted;
        Pending(String key,UserAccount actor,ApprovalService.Preview preview,Instant expiry){
            intentKey=key;id=UUID.randomUUID().toString();userId=actor.id();authVersion=actor.authVersion();companyId=actor.companyId();role=actor.role();expiresAt=expiry;this.preview=preview;
        }
        Pending(PendingSnapshot data){intentKey=data.intentKey;id=data.id;userId=data.userId;authVersion=data.authVersion;companyId=data.companyId;role=data.role;expiresAt=data.expiresAt;preview=data.preview.restore();cancelled=data.cancelled;submitted=data.submitted;}
        @Serial private Object writeReplace(){return new PendingSnapshot(intentKey,id,userId,authVersion,companyId,role,expiresAt,PreviewSnapshot.from(preview),cancelled,submitted);}
        Confirmation view(){return new Confirmation(id,expiresAt,preview);}
    }
    private record DraftSnapshot(ApprovalRules.Type type,LocalDateTime startAt,LocalDateTime endAt,ApprovalRules.PunchType punchType,Long workplaceId,String reason) implements Serializable {
        @Serial private static final long serialVersionUID=1L;
        static DraftSnapshot from(ApprovalRules.Draft d){return new DraftSnapshot(d.type(),d.startAt(),d.endAt(),d.punchType(),d.workplaceId(),d.reason());}
        ApprovalRules.Draft restore(){return new ApprovalRules.Draft(type,startAt,endAt,punchType,workplaceId,reason);}
    }
    private record PreviewSnapshot(long applicantId,String applicantName,Long companyId,Long departmentId,DraftSnapshot draft,long requestedMinutes,boolean validated,boolean stored,boolean attendanceAdjusted) implements Serializable {
        @Serial private static final long serialVersionUID=1L;
        static PreviewSnapshot from(ApprovalService.Preview p){return new PreviewSnapshot(p.applicantId(),p.applicantName(),p.companyId(),p.departmentId(),DraftSnapshot.from(p.draft()),p.requestedMinutes(),p.validated(),p.stored(),p.attendanceAdjusted());}
        ApprovalService.Preview restore(){return new ApprovalService.Preview(applicantId,applicantName,companyId,departmentId,draft.restore(),requestedMinutes,validated,stored,attendanceAdjusted);}
    }
    private record PendingSnapshot(String intentKey,String id,long userId,long authVersion,Long companyId,String role,Instant expiresAt,PreviewSnapshot preview,boolean cancelled,boolean submitted) implements Serializable {
        @Serial private static final long serialVersionUID=1L;
        @Serial private Object readResolve(){return new Pending(this);}
    }
    private static final class SessionState implements Serializable {
        @Serial private static final long serialVersionUID=1L;
        final Map<String,Pending> items=new LinkedHashMap<>();
    }
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
                if(shared!=null)shared.prepared(session.getId(),p.id,p.expiresAt);
                session.setAttribute(ATTRIBUTE,state);
                return p.view();
            }
            if(state.items.size()>=LIMIT)throw BusinessException.conflict("待确认草稿过多，请稍后重试");
            var pending=new Pending(input.intentKey(),account,preview,clock.instant().plus(Duration.ofMinutes(10)));
            if(shared!=null)shared.prepared(session.getId(),pending.id,pending.expiresAt);
            state.items.put(pending.id,pending);session.setAttribute(ATTRIBUTE,state);return pending.view();
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
            var claim=shared==null?null:shared.claim(session.getId(),pending.id);
            boolean committed=false;
            try {
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
            committed=true;pending.submitted=true;
            if(shared!=null)shared.submitted(session.getId(),pending.id,claim);
            session.setAttribute(ATTRIBUTE,state);return result;
            } catch(RuntimeException error) {
                if(shared!=null && !committed)shared.failed(session.getId(),pending.id,claim);
                throw error;
            }
        }
    }
    public void cancel(Authentication actor,HttpSession session,String id){
        identity.current(actor);var state=state(session);
        synchronized(state){
            var pending=required(state,id);unchanged(pending,scope.actor(actor));
            if(pending.submitted)throw BusinessException.conflict("申请已提交，请在我的申请中查看或撤回");
            if(shared!=null)shared.cancel(session.getId(),pending.id);
            pending.cancelled=true;session.setAttribute(ATTRIBUTE,state);
        }
    }
    private Pending required(SessionState state,String id){var p=state.items.get(id);if(p==null)throw BusinessException.missing();return p;}
    private void unchanged(Pending p,UserAccount actor){
        if(p.userId!=actor.id() || p.authVersion!=actor.authVersion() || !Objects.equals(p.companyId,actor.companyId()) || !p.role.equals(actor.role()))
            throw BusinessException.forbidden();
    }
}
