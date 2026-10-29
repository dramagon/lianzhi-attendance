package cn.lianzhi.attendance;

import cn.lianzhi.attendance.approval.ApprovalRules;
import cn.lianzhi.attendance.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static cn.lianzhi.attendance.approval.ApprovalRules.*;
import static org.assertj.core.api.Assertions.*;

class ApprovalRulesTest {
    final LocalDateTime start=LocalDateTime.of(2026,10,27,9,0),end=start.plusHours(8);
    Draft leave() { return new Draft(Type.LEAVE,start,end,null,null,"  请假事由  "); }
    Request request(State state) { return new Request(10,3,1,leave(),state,0,start.minusDays(1)); }
    Actor hr() { return new Actor(2,1L,"HR"); }
    @Test void leaveAndOvertimeNormalizeReasonAndReportRequestedMinutes() {
        assertThat(validate(leave()).reason()).isEqualTo("请假事由");
        assertThat(requestedMinutes(leave())).isEqualTo(480);
        assertThat(requestedMinutes(new Draft(Type.OVERTIME,start,end.plusSeconds(1),null,null,"加班"))).isEqualTo(481);
    }
    @Test void missingPunchIsAnInstantWithDirectionAndPlace() {
        var input=new Draft(Type.MISSING_PUNCH,start,null,PunchType.IN,1L,"忘记打卡");
        assertThat(validate(input)).isEqualTo(input); assertThat(requestedMinutes(input)).isZero();
    }
    @Test void reversedZeroAndUnboundedIntervalsAreRejected() {
        for (var until:List.of(start,start.minusMinutes(1),start.plusSeconds(59),start.plusDays(32))) {
            assertThatThrownBy(()->validate(new Draft(Type.LEAVE,start,until,null,null,"事由"))).isInstanceOf(BusinessException.class);
        }
    }
    @Test void mismatchedFieldsAndFractionalSecondsAreRejected() {
        for (var draft:List.of(new Draft(Type.LEAVE,start,end,PunchType.IN,1L,"事由"),
                new Draft(Type.MISSING_PUNCH,start,end,PunchType.IN,1L,"事由"),
                new Draft(Type.MISSING_PUNCH,start,null,null,1L,"事由"),
                new Draft(Type.MISSING_PUNCH,start,null,PunchType.IN,null,"事由"),
                new Draft(Type.LEAVE,start.plusNanos(1),end,null,null,"事由"))) {
            assertThatThrownBy(()->validate(draft)).isInstanceOf(BusinessException.class);
        }
    }
    @Test void blankAndOverlongReasonsAreRejected() {
        for (String reason:List.of(" ","字".repeat(501))) assertThatThrownBy(()->validate(new Draft(Type.LEAVE,start,end,null,null,reason))).isInstanceOf(BusinessException.class);
    }
    @Test void overlappingPendingAndApprovedIntervalsConflictButAdjacentDoNot() {
        for (State state:List.of(State.PENDING,State.APPROVED)) assertThatThrownBy(()->requireNoOverlap(3,leave(),List.of(request(state)))).isInstanceOf(BusinessException.class);
        var adjacent=new Draft(Type.OVERTIME,end,end.plusHours(1),null,null,"加班");
        assertThatCode(()->requireNoOverlap(3,adjacent,List.of(request(State.PENDING)))).doesNotThrowAnyException();
    }
    @Test void rejectedWithdrawnAndOtherApplicantsDoNotBlockIntervals() {
        assertThatCode(()->requireNoOverlap(3,leave(),List.of(request(State.REJECTED),request(State.WITHDRAWN)))).doesNotThrowAnyException();
        assertThatCode(()->requireNoOverlap(9,leave(),List.of(request(State.PENDING)))).doesNotThrowAnyException();
    }
    @Test void duplicatePunchRequestsAreRejected() {
        var punch=new Draft(Type.MISSING_PUNCH,start,null,PunchType.IN,1L,"补卡");
        var previous=new Request(11,3,1,punch,State.PENDING,0,start.minusDays(1));
        assertThatThrownBy(()->requireNoOverlap(3,punch,List.of(previous))).isInstanceOf(BusinessException.class);
        assertThatCode(()->requireNoOverlap(3,new Draft(Type.MISSING_PUNCH,start,null,PunchType.OUT,1L,"补卡"),List.of(previous))).doesNotThrowAnyException();
    }
    @Test void sameCompanyHrApprovesWithTraceWithoutMutatingOriginal() {
        var original=request(State.PENDING);
        var result=transition(original,Action.APPROVE,hr(),start,"同意",0);
        assertThat(result.request().state()).isEqualTo(State.APPROVED);
        assertThat(result.request().version()).isEqualTo(1);
        assertThat(result.transition().actorId()).isEqualTo(2);
        assertThat(result.transition().occurredAt()).isEqualTo(start);
        assertThat(original.state()).isEqualTo(State.PENDING);
    }
    @Test void applicantCannotApproveSelfEvenWhenAdmin() {
        assertThatThrownBy(()->transition(request(State.PENDING),Action.APPROVE,new Actor(3,1L,"ADMIN"),start,"",0)).isInstanceOf(BusinessException.class);
    }
    @Test void employeesAndForeignCompanyHrCannotReview() {
        for (Actor actor:List.of(new Actor(4,1L,"EMPLOYEE"),new Actor(4,2L,"HR"),new Actor(4,null,"HR"))) {
            assertThatThrownBy(()->transition(request(State.PENDING),Action.APPROVE,actor,start,"",0)).isInstanceOf(BusinessException.class);
        }
    }
    @Test void platformAdminMayReviewOtherCompanies() {
        assertThat(transition(request(State.PENDING),Action.APPROVE,new Actor(1,null,"ADMIN"),start,"",0).request().state()).isEqualTo(State.APPROVED);
    }
    @Test void rejectionRequiresAReason() {
        assertThatThrownBy(()->transition(request(State.PENDING),Action.REJECT,hr(),start," ",0)).isInstanceOf(BusinessException.class);
        assertThat(transition(request(State.PENDING),Action.REJECT,hr(),start,"时间不符",0).request().state()).isEqualTo(State.REJECTED);
    }
    @Test void onlyApplicantMayWithdrawPendingRequests() {
        assertThat(transition(request(State.PENDING),Action.WITHDRAW,new Actor(3,1L,"EMPLOYEE"),start,"",0).request().state()).isEqualTo(State.WITHDRAWN);
        assertThatThrownBy(()->transition(request(State.PENDING),Action.WITHDRAW,hr(),start,"",0)).isInstanceOf(BusinessException.class);
    }
    @Test void terminalRequestsCannotBeReviewedOrWithdrawnAgain() {
        for(State state:List.of(State.APPROVED,State.REJECTED,State.WITHDRAWN)) {
            assertThatThrownBy(()->transition(request(state),Action.APPROVE,hr(),start,"",0)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(()->transition(request(state),Action.WITHDRAW,new Actor(3,1L,"EMPLOYEE"),start,"",0)).isInstanceOf(BusinessException.class);
        }
    }
    @Test void staleVersionsAreRejectedBeforeAnyTransition() {
        assertThatThrownBy(()->transition(request(State.PENDING),Action.APPROVE,hr(),start,"",9)).isInstanceOf(BusinessException.class);
    }
}
