package cn.lianzhi.attendance.approval;

import org.springframework.security.core.Authentication;
import java.time.LocalDateTime;

/** B实现；A仅在已鉴权的真实审批事务中调用，同一数据源与READ_COMMITTED事务。 */
public interface ApprovalAttendancePort {
    record Adjustment(long approvalId,long applicantId,long companyId,ApprovalRules.Draft draft,long approverId,LocalDateTime approvedAt) {}
    boolean available();
    void validate(Authentication actor,Adjustment adjustment);
    /** 按approvalId幂等、保留原始事件，并参加调用方同一数据库事务。 */
    void apply(Authentication actor,Adjustment adjustment);
}
