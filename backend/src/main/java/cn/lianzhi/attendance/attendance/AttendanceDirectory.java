package cn.lianzhi.attendance.attendance;

import java.time.LocalDate;
import java.util.List;

/** A supplies a Spring bean after V2. Every method must enforce employee/company scope. No permissive default. */
public interface AttendanceDirectory {
    record Workplace(long id, String name, GeoFence.Point point, int radiusMeters, boolean faceRequired) {}
    record Assignment(Workplace workplace, AttendanceRules.Rule rule, LocalDate businessDate, boolean holiday) {}
    List<Workplace> workplaces(long userId);
    Assignment forPunch(long userId, long workplaceId, java.time.Instant now);
    void requireManager(long managerId, long workplaceId);
}
