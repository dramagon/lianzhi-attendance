package cn.lianzhi.attendance.attendance;

import cn.lianzhi.attendance.employee.EmployeeService;
import cn.lianzhi.attendance.organization.DataScope;
import cn.lianzhi.attendance.workplace.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class AttendanceSchedules {
    public record Schedule(long userId, LocalDate businessDate, long workplaceId, AttendanceRules.Rule rule, boolean holiday) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DataScope scope;
    private final EmployeeService employees;
    private final WorkplaceService workplaces;
    public AttendanceSchedules(JdbcTemplate jdbc,ObjectMapper json,DataScope scope,EmployeeService employees,WorkplaceService workplaces) {
        this.jdbc=jdbc;this.json=json;this.scope=scope;this.employees=employees;this.workplaces=workplaces;
    }
    public String encode(Object value) { try {return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("无法保存考勤内容",e);} }
    public AttendanceRules.Rule decode(String value) {try{return json.readValue(value,AttendanceRules.Rule.class);}catch(Exception e){throw new IllegalStateException("排班规则读取失败",e);} }
    public List<Schedule> list(long user,LocalDate from,LocalDate to) {
        return jdbc.query("SELECT * FROM attendance_schedule WHERE user_id=? AND business_date BETWEEN ? AND ? ORDER BY business_date",
            (rs,n)->new Schedule(rs.getLong("user_id"),rs.getDate("business_date").toLocalDate(),rs.getLong("workplace_id"),decode(rs.getString("rule_json")),rs.getBoolean("holiday")),user,from,to);
    }
    public Schedule get(long user,LocalDate date) {return list(user,date,date).stream().findFirst().orElseThrow(()->new IllegalArgumentException("该日期尚未安排班次"));}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void save(Authentication actor,Schedule schedule) {
        scope.manager(actor);
        if(schedule==null || schedule.businessDate()==null || schedule.rule()==null)throw new IllegalArgumentException("请填写员工、业务日期和班次");
        var employee=employees.get(actor,schedule.userId());
        if(!employee.enabled() || employee.companyId()==null)throw new IllegalArgumentException("员工须启用且归属有效公司");
        if(workplaces.activeForEmployee(actor,schedule.userId()).stream().noneMatch(w->w.id()==schedule.workplaceId()))throw new IllegalArgumentException("员工尚未绑定该有效场所");
        if(schedule.businessDate().isBefore(LocalDate.now(AttendanceRules.ZONE)))throw new IllegalArgumentException("不能改写过去日期的正式排班");
        jdbc.queryForObject("SELECT id FROM sys_user WHERE id=? FOR UPDATE",Long.class,schedule.userId());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_event WHERE user_id=? AND business_date=?",Long.class,schedule.userId(),schedule.businessDate())>0)
            throw new AttendanceProblem("SCHEDULE_LOCKED","已有打卡记录，不能改写班次",org.springframework.http.HttpStatus.CONFLICT);
        for(String draftJson:jdbc.queryForList("SELECT draft_json FROM attendance_adjustment WHERE user_id=?",String.class,schedule.userId())) {
            try {var d=json.readValue(draftJson,cn.lianzhi.attendance.approval.ApprovalRules.Draft.class);var from=d.startAt().toLocalDate().minusDays(1);var to=d.endAt()==null?d.startAt().toLocalDate():d.endAt().toLocalDate();if(!schedule.businessDate().isBefore(from) && !schedule.businessDate().isAfter(to))throw new AttendanceProblem("SCHEDULE_LOCKED","班次已有审批调整，不能改写排班",org.springframework.http.HttpStatus.CONFLICT);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("审批调整读取失败",e);}
        }
        var start=schedule.businessDate().atTime(schedule.rule().start());
        var end=AttendanceRules.finish(schedule.businessDate(),schedule.rule().start(),schedule.rule().end());
        for(var old:list(schedule.userId(),schedule.businessDate().minusDays(1),schedule.businessDate().plusDays(1))) {
            if(old.businessDate().equals(schedule.businessDate()))continue;
            if(start.isBefore(AttendanceRules.finish(old.businessDate(),old.rule().start(),old.rule().end())) && end.isAfter(old.businessDate().atTime(old.rule().start())))throw new IllegalArgumentException("排班与相邻日期的班次重叠");
        }
        jdbc.update("DELETE FROM attendance_schedule WHERE user_id=? AND business_date=?",schedule.userId(),schedule.businessDate());
        jdbc.update("INSERT INTO attendance_schedule(user_id,business_date,workplace_id,rule_json,holiday,created_by) VALUES(?,?,?,?,?,?)",schedule.userId(),schedule.businessDate(),schedule.workplaceId(),encode(schedule.rule()),schedule.holiday(),scope.actor(actor).id());
    }
    public Schedule current(long user,Instant instant) {
        LocalDateTime now=LocalDateTime.ofInstant(instant,AttendanceRules.ZONE);
        return list(user,now.toLocalDate().minusDays(1),now.toLocalDate().plusDays(1)).stream()
            .filter(s->!now.isBefore(s.businessDate().atTime(s.rule().start()).minusHours(4)) && !now.isAfter(AttendanceRules.finish(s.businessDate(),s.rule().start(),s.rule().end()).plusHours(4)))
            .min(Comparator.comparingLong(s->{var start=s.businessDate().atTime(s.rule().start());var end=AttendanceRules.finish(s.businessDate(),s.rule().start(),s.rule().end());return now.isBefore(start)?Duration.between(now,start).getSeconds():now.isAfter(end)?Duration.between(end,now).getSeconds():0L;}))
            .orElseThrow(()->AttendanceProblem.unavailable("当前没有可打卡的正式班次，请联系管理员排班"));
    }
}
