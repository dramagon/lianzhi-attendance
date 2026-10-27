package cn.lianzhi.attendance.approval;

import org.springframework.security.core.Authentication;
import java.time.LocalDateTime;

/** A/B待确认的候选接入契约。当前无实现，不能当作B已提供的调整服务。 */
public interface ApprovalAttendancePort {
    record Adjustment(long approvalId,long applicantId,long companyId,ApprovalRules.Draft draft,long approverId,LocalDateTime approvedAt) {}
    boolean available();
    void validate(Authentication actor,Adjustment adjustment);
    /** 未来实现按approvalId幂等、保留原始事件，并参加调用方同一数据库事务。 */
    void apply(Authentication actor,Adjustment adjustment);
}
