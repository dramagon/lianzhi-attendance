package cn.lianzhi.attendance.assistant;

import cn.lianzhi.attendance.approval.*;
import cn.lianzhi.attendance.attendance.*;
import cn.lianzhi.attendance.common.PageResult;
import cn.lianzhi.attendance.knowledge.retrieval.KnowledgeIdentity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;

/** Explicit read-only queries. Never interprets model-generated tools or recalculates B's data. */
@Service
public class AssistantQueries {
    private final KnowledgeIdentity identity;
    private final AttendanceDays days;
    private final ApprovalService approvals;
    public AssistantQueries(KnowledgeIdentity identity,AttendanceDays days,ApprovalService approvals){this.identity=identity;this.days=days;this.approvals=approvals;}
    public List<AttendanceDays.Day> attendance(Authentication actor,Long userId,LocalDate from,LocalDate to){
        var current=identity.current(actor);
        return days.list(actor,userId==null?current.userId():userId,from,to);
    }
    public PageResult<ApprovalStore.Entry> applications(Authentication actor,String state,int page,int size){
        identity.current(actor);return approvals.list(actor,false,state,page,size);
    }
    public ApprovalService.Detail application(Authentication actor,long id){
        identity.current(actor);return approvals.detail(actor,id);
    }
}
