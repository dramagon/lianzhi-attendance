package cn.lianzhi.attendance.approval;

import cn.lianzhi.attendance.common.*;
import java.time.*;
import java.util.*;

/** 无持久化副作用的审批策略；版本校验仍须在实际存储层以CAS更新落实。 */
public final class ApprovalRules {
    private ApprovalRules() {}
    public enum Type { LEAVE, MISSING_PUNCH, OVERTIME }
    public enum PunchType { IN, OUT }
    public enum State { PENDING, APPROVED, REJECTED, WITHDRAWN }
    public enum Action { APPROVE, REJECT, WITHDRAW }
    public record Draft(Type type,LocalDateTime startAt,LocalDateTime endAt,PunchType punchType,Long workplaceId,String reason) {}
    /** 必须由服务端身份与员工服务构造，不能由客户端传入。 */
    public record Actor(long userId,Long companyId,String role) {}
    public record Request(long id,long applicantId,long companyId,Draft draft,State state,long version,LocalDateTime createdAt) {}
    public record Transition(long requestId,State fromState,State toState,long actorId,LocalDateTime occurredAt,String reason,long version) {}
    public record Result(Request request,Transition transition) {}

    public static Draft validate(Draft input) {
        Inputs.required(input,"申请内容"); Inputs.required(input.type(),"申请类型"); Inputs.required(input.startAt(),"申请时间");
        String reason=Inputs.text(input.reason(),500,"申请事由");
        if (input.startAt().getNano()!=0 || (input.endAt()!=null && input.endAt().getNano()!=0)) throw BusinessException.invalid("申请时间最多精确到秒");
        if (input.type()==Type.MISSING_PUNCH) {
            Inputs.required(input.punchType(),"补卡方向");
            if (input.workplaceId()==null || input.workplaceId()<1) throw BusinessException.invalid("补卡必须选择办公场所");
            if (input.endAt()!=null) throw BusinessException.invalid("补卡仅填写一次打卡时间，无需结束时间");
        } else {
            Inputs.required(input.endAt(),"结束时间");
            var duration=Duration.between(input.startAt(),input.endAt());
            if (duration.compareTo(Duration.ofMinutes(1))<0 || duration.compareTo(Duration.ofDays(31))>0) throw BusinessException.invalid("结束须晚于开始，申请时段为1分钟至31天");
            if (input.punchType()!=null || input.workplaceId()!=null) throw BusinessException.invalid("请假和加班无需补卡方向或场所");
        }
        return new Draft(input.type(),input.startAt(),input.endAt(),input.punchType(),input.workplaceId(),reason);
    }
    public static long requestedMinutes(Draft input) {
        Draft draft=validate(input);
        return draft.type()==Type.MISSING_PUNCH?0:(Duration.between(draft.startAt(),draft.endAt()).getSeconds()+59)/60;
    }
    public static void requireNoOverlap(long applicantId,Draft input,List<Request> existing) {
        Draft draft=validate(input);
        for (var request:existing) {
            if (request.applicantId()!=applicantId || !Set.of(State.PENDING,State.APPROVED).contains(request.state())) continue;
            Draft old=validate(request.draft());
            if (draft.type()==Type.MISSING_PUNCH || old.type()==Type.MISSING_PUNCH) {
                if (draft.type()==old.type() && draft.startAt().equals(old.startAt()) && draft.punchType()==old.punchType() && Objects.equals(draft.workplaceId(),old.workplaceId())) throw BusinessException.conflict("已有同一时间、方向和场所的补卡申请");
            } else if (draft.startAt().isBefore(old.endAt()) && old.startAt().isBefore(draft.endAt())) {
                throw BusinessException.conflict("申请时段与待审批或已通过的请假/加班重叠");
            }
        }
    }
    public static Result transition(Request current,Action action,Actor actor,LocalDateTime serverTime,String note,long expectedVersion) {
        Inputs.required(current,"申请"); Inputs.required(action,"操作"); Inputs.required(actor,"操作人"); Inputs.required(serverTime,"服务端时间");
        if (current.version()!=expectedVersion) throw BusinessException.conflict("申请已被更新，请刷新后重试");
        if (current.state()!=State.PENDING) throw BusinessException.conflict("仅待审批申请可处理；已生效申请暂不支持撤销");
        State next;
        if (action==Action.WITHDRAW) {
            if (actor.userId()!=current.applicantId()) throw BusinessException.forbidden();
            next=State.WITHDRAWN;
        } else {
            if (!Set.of("ADMIN","HR").contains(actor.role()) || actor.userId()==current.applicantId()) throw BusinessException.forbidden();
            if (!actor.role().equals("ADMIN") && !Objects.equals(actor.companyId(),current.companyId())) throw BusinessException.forbidden();
            next=action==Action.APPROVE?State.APPROVED:State.REJECTED;
        }
        String explanation=note==null?"":note.trim();
        if (explanation.length()>500) throw BusinessException.invalid("处理说明不能超过500字");
        if (action==Action.REJECT && explanation.isBlank()) throw BusinessException.invalid("拒绝申请时请填写原因");
        long version=Math.addExact(current.version(),1);
        return new Result(new Request(current.id(),current.applicantId(),current.companyId(),current.draft(),next,version,current.createdAt()),
                new Transition(current.id(),current.state(),next,actor.userId(),serverTime,explanation,version));
    }
}
