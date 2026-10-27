package cn.lianzhi.attendance.attendance;

import java.time.LocalDate;
import java.util.List;

/** B adapter consumes A services after V2. Every method must enforce employee/company scope. No permissive default. */
public interface AttendanceDirectory {
    record Workplace(long id, String name, GeoFence.Point point, int radiusMeters, boolean faceRequired) {}
    record Assignment(Workplace workplace, AttendanceRules.Rule rule, LocalDate businessDate, boolean holiday) {}
    List<Workplace> workplaces(long userId);
    default List<Workplace> managedWorkplaces(long managerId) { return List.of(); }
    Assignment forPunch(long userId, long workplaceId, java.time.Instant now);
    default java.time.LocalDateTime adjustedPunch(long userId, LocalDate date, String type) { return null; }
    void requireManager(long managerId, long workplaceId);
}
