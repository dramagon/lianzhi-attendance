package cn.lianzhi.attendance.attendance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DuplicateKeyException;
import java.sql.*;
import java.time.*;
import java.util.*;

@Repository
public class AttendanceStore {
    private final JdbcTemplate jdbc;
    public AttendanceStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Event(long id, long userId, long workplaceId, LocalDate businessDate, String type,
                        Instant occurredAt, String requestId, String nonce, double distanceMeters) {}
    public record Page(List<Event> items, long total, int page, int size) {}
    public boolean ready() {
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c -> {
            for (String table : List.of("attendance_event")) {
                boolean found = false;
                try (ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
                    while (rs.next()) if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) found = true;
                }
                if (!found) return false;
            }
            return true;
        }));
    }
    public void lockUser(long userId) {
        Boolean enabled = jdbc.queryForObject("SELECT enabled FROM sys_user WHERE id=? FOR UPDATE", Boolean.class, userId);
        if (!Boolean.TRUE.equals(enabled)) throw new org.springframework.security.access.AccessDeniedException("账号不可用");
    }
    public Optional<Event> byRequest(long userId, String requestId) {
        return jdbc.query("SELECT * FROM attendance_event WHERE user_id=? AND request_id=?", this::map, userId, requestId).stream().findFirst();
    }
    public List<Event> day(long userId, LocalDate date) {
        return jdbc.query("SELECT * FROM attendance_event WHERE user_id=? AND business_date=? ORDER BY occurred_at,id", this::map, userId, date);
    }
    public Event append(long userId, long place, LocalDate date, String type, Instant now, String request, String nonce, double distance) {
        try {
            jdbc.update("INSERT INTO attendance_event(user_id,workplace_id,business_date,event_type,occurred_at,request_id,qr_nonce,distance_meters) VALUES(?,?,?,?,?,?,?,?)",
                    userId, place, date, type, Timestamp.from(now), request, nonce, distance);
        } catch (DuplicateKeyException e) {
            Optional<Event> replay = byRequest(userId, request);
            if (replay.isPresent()) {
                Event old = replay.get();
                if (old.workplaceId == place && old.type.equals(type)) return old;
            }
            throw new AttendanceProblem("DUPLICATE_PUNCH", "本次请求或该班次已打卡，请刷新记录，勿重复提交", org.springframework.http.HttpStatus.CONFLICT);
        }
        return byRequest(userId, request).orElseThrow();
    }
    public Page page(long userId, LocalDate from, LocalDate to, int page, int size) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM attendance_event WHERE user_id=? AND business_date BETWEEN ? AND ?", Long.class, userId, from, to);
        List<Event> events = jdbc.query("SELECT * FROM attendance_event WHERE user_id=? AND business_date BETWEEN ? AND ? ORDER BY occurred_at DESC,id DESC LIMIT ? OFFSET ?",
                this::map, userId, from, to, size, (page - 1L) * size);
        return new Page(events, total == null ? 0 : total, page, size);
    }
    private Event map(ResultSet rs, int row) throws SQLException {
        return new Event(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("workplace_id"), rs.getDate("business_date").toLocalDate(),
                rs.getString("event_type"), rs.getTimestamp("occurred_at").toInstant(), rs.getString("request_id"), rs.getString("qr_nonce"), rs.getDouble("distance_meters"));
    }
}
