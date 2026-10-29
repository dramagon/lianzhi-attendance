package cn.lianzhi.attendance.approval;

import cn.lianzhi.attendance.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

@Repository
public class ApprovalStore {
    private final JdbcTemplate jdbc;
    public ApprovalStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Entry(ApprovalRules.Request request,Long departmentId,String requestKey,String applicantName) {}
    private static LocalDateTime time(ResultSet rs,String name) throws SQLException {
        Timestamp value=rs.getTimestamp(name); return value==null?null:value.toLocalDateTime();
    }
    private final RowMapper<Entry> mapper=(rs,n)->new Entry(new ApprovalRules.Request(rs.getLong("id"),rs.getLong("applicant_id"),rs.getLong("company_id"),
        new ApprovalRules.Draft(ApprovalRules.Type.valueOf(rs.getString("request_type")),time(rs,"start_at"),time(rs,"end_at"),rs.getString("punch_type")==null?null:ApprovalRules.PunchType.valueOf(rs.getString("punch_type")),(Long)rs.getObject("workplace_id"),rs.getString("reason")),
        ApprovalRules.State.valueOf(rs.getString("state")),rs.getLong("version"),time(rs,"created_at")),(Long)rs.getObject("department_id"),rs.getString("request_key"),rs.getString("applicant_name"));
    private static final String SELECT="SELECT a.*,u.display_name AS applicant_name FROM approval_request a JOIN sys_user u ON u.id=a.applicant_id ";
    public void lockEmployee(long id) { jdbc.queryForObject("SELECT id FROM sys_user WHERE id=? FOR UPDATE",Long.class,id); }
    public void lockRequest(long id) { jdbc.queryForObject("SELECT id FROM approval_request WHERE id=? FOR UPDATE",Long.class,id); }
    public Entry get(long id) {
        var rows=jdbc.query(SELECT+"WHERE a.id=?",mapper,id);
        if(rows.isEmpty()) throw new BusinessException(org.springframework.http.HttpStatus.NOT_FOUND,"申请不存在");
        return rows.get(0);
    }
    public Entry byKey(long user,String key) { return jdbc.query(SELECT+"WHERE a.applicant_id=? AND a.request_key=?",mapper,user,key).stream().findFirst().orElse(null); }
    public List<ApprovalRules.Request> active(long user) { return jdbc.query(SELECT+"WHERE a.applicant_id=? AND a.state IN ('PENDING','APPROVED')",mapper,user).stream().map(Entry::request).toList(); }
    public Entry insert(long user,long company,Long department,String key,ApprovalRules.Draft d,LocalDateTime now) {
        jdbc.update("INSERT INTO approval_request(applicant_id,company_id,department_id,request_key,request_type,start_at,end_at,punch_type,workplace_id,reason,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            user,company,department,key,d.type().name(),d.startAt(),d.endAt(),d.punchType()==null?null:d.punchType().name(),d.workplaceId(),d.reason(),now,now);
        var entry=byKey(user,key); audit(new ApprovalRules.Transition(entry.request().id(),ApprovalRules.State.PENDING,ApprovalRules.State.PENDING,user,now,"提交申请",0)); return entry;
    }
    public void change(ApprovalRules.Result result) {
        var r=result.request();
        int count=jdbc.update("UPDATE approval_request SET state=?,version=?,updated_at=? WHERE id=? AND state='PENDING' AND version=?",r.state().name(),r.version(),result.transition().occurredAt(),r.id(),r.version()-1);
        if(count!=1)throw BusinessException.conflict("申请已被更新，请刷新后重试");
        audit(result.transition());
    }
    private void audit(ApprovalRules.Transition t) { jdbc.update("INSERT INTO approval_transition(approval_id,from_state,to_state,actor_id,occurred_at,reason,version) VALUES(?,?,?,?,?,?,?)",t.requestId(),t.fromState().name(),t.toState().name(),t.actorId(),t.occurredAt(),t.reason(),t.version()); }
    public List<ApprovalRules.Transition> history(long id) { return jdbc.query("SELECT * FROM approval_transition WHERE approval_id=? ORDER BY version",(rs,n)->new ApprovalRules.Transition(rs.getLong("approval_id"),ApprovalRules.State.valueOf(rs.getString("from_state")),ApprovalRules.State.valueOf(rs.getString("to_state")),rs.getLong("actor_id"),time(rs,"occurred_at"),rs.getString("reason"),rs.getLong("version")),id); }
    public cn.lianzhi.attendance.common.PageResult<Entry> list(Long user,Long company,String state,int page,int size) {
        var args=new ArrayList<Object>();String where="WHERE 1=1 ";
        if(user!=null){where+="AND a.applicant_id=? ";args.add(user);}
        if(company!=null){where+="AND a.company_id=? AND u.company_id=? ";args.add(company);args.add(company);}
        if(state.equals("PROCESSED"))where+="AND a.state<>'PENDING' ";
        else if(!state.equals("ALL")){where+="AND a.state=? ";args.add(state);}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM approval_request a JOIN sys_user u ON u.id=a.applicant_id "+where,Long.class,args.toArray());
        args.add(size);args.add((long)(page-1)*size);
        return new cn.lianzhi.attendance.common.PageResult<>(jdbc.query(SELECT+where+"ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?",mapper,args.toArray()),total,page,size);
    }
}
