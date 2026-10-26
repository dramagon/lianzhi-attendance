package cn.lianzhi.attendance.attendance;

import java.time.*;
import java.util.*;

/** One shift per business date. Work is capped to shift time; overtime requires approval. */
public final class AttendanceRules {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private AttendanceRules() {}
    public record Rule(LocalTime start, LocalTime end, LocalTime breakStart, LocalTime breakEnd,
                       int graceMinutes, Set<DayOfWeek> workdays) {
        public Rule {
            if (start == null || end == null || start.equals(end)) throw invalid("班次开始和结束时间须填写且不能相同");
            if (start.getSecond() != 0 || end.getSecond() != 0 || start.getNano() != 0 || end.getNano() != 0)
                throw invalid("班次时间精确到分钟");
            if (graceMinutes < 0 || graceMinutes > 120) throw invalid("迟到宽限须为 0–120 分钟");
            if (workdays == null) throw invalid("请指定每周工作日");
            workdays = Set.copyOf(workdays);
            if ((breakStart == null) != (breakEnd == null)) throw invalid("休息开始和结束须同时填写或同时留空");
            if (breakStart != null) {
                LocalDate date = LocalDate.of(2026, 1, 1);
                LocalDateTime a = at(date, start, breakStart), b = at(date, start, breakEnd);
                if (!b.isAfter(a) || b.isAfter(finish(date, start, end))) throw invalid("休息时间须在班次内且结束晚于开始");
            }
        }
    }
    public record Input(LocalDate date, Rule rule, LocalDateTime checkIn, LocalDateTime checkOut,
                        boolean holiday, boolean finalized) {}
    public record Result(String status, int lateMinutes, int earlyMinutes, long workMinutes,
                         long breakMinutes, boolean overnight, List<String> notes) {}
    public static Result calculate(Input input) {
        if (input == null || input.date == null || input.rule == null) throw invalid("请填写业务日期与班次规则");
        Rule r = input.rule;
        LocalDateTime start = input.date.atTime(r.start), end = finish(input.date, r.start, r.end);
        boolean overnight = end.toLocalDate().isAfter(input.date);
        LocalDateTime in = input.checkIn, out = input.checkOut;
        for (LocalDateTime event : Arrays.asList(in, out)) {
            if (event != null && (event.isBefore(start.minusHours(12)) || event.isAfter(end.plusHours(12))))
                throw invalid("打卡时间超出班次前后 12 小时的试算范围，请检查日期");
        }
        if (in != null && out != null && !out.isAfter(in)) throw invalid("签退须晚于签到，请填写完整日期与时间");
        if (input.holiday || !r.workdays.contains(input.date.getDayOfWeek()))
            return new Result("REST_DAY", 0, 0, 0, 0, overnight, List.of("休息日不计迟到早退；额外出勤须走加班审批"));
        if (in == null || out == null) {
            String status = !input.finalized ? "PENDING" : in == null && out == null ? "ABSENT" : "MISSING_PUNCH";
            return new Result(status, 0, 0, 0, 0, overnight,
                    List.of(!input.finalized ? "班次尚未结算，不提前判定缺勤或缺卡" : "缺少完整打卡，不推算有效工时"));
        }
        long lateSeconds = Math.max(0, Duration.between(start, in).getSeconds());
        int late = lateSeconds > r.graceMinutes * 60L ? (int) ((lateSeconds + 59) / 60) : 0;
        int early = (int) ((Math.max(0, Duration.between(out, end).getSeconds()) + 59) / 60);
        LocalDateTime lo = in.isAfter(start) ? in : start, hi = out.isBefore(end) ? out : end;
        long span = Math.max(0, Duration.between(lo, hi).getSeconds()), rest = 0;
        if (r.breakStart != null && span > 0) {
            LocalDateTime bs = at(input.date, r.start, r.breakStart), be = at(input.date, r.start, r.breakEnd);
            LocalDateTime left = lo.isAfter(bs) ? lo : bs, right = hi.isBefore(be) ? hi : be;
            rest = Math.max(0, Duration.between(left, right).getSeconds());
        }
        String status = late > 0 && early > 0 ? "LATE_AND_EARLY" : late > 0 ? "LATE" : early > 0 ? "EARLY" : "NORMAL";
        return new Result(status, late, early, (span - rest) / 60, rest / 60, overnight,
                List.of("有效工时按班次内实际出勤与休息重叠计算，向下取整到分钟", "超出宽限按相对班次开始的总分钟数计迟到，不自动计算加班"));
    }
    public static LocalDateTime finish(LocalDate date, LocalTime start, LocalTime end) {
        return (end.isBefore(start) ? date.plusDays(1) : date).atTime(end);
    }
    private static LocalDateTime at(LocalDate date, LocalTime start, LocalTime time) {
        return (time.isBefore(start) ? date.plusDays(1) : date).atTime(time);
    }
    public static final class InvalidRule extends IllegalArgumentException {
        public InvalidRule(String message) { super(message); }
    }
    private static InvalidRule invalid(String message) { return new InvalidRule(message); }
}
