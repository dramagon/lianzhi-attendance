package cn.lianzhi.attendance.attendance;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AttendanceRulesTest {
    private final LocalDate date = LocalDate.of(2026, 10, 26);
    private AttendanceRules.Rule standard(int grace) {
        return new AttendanceRules.Rule(LocalTime.of(9,0), LocalTime.of(18,0), LocalTime.NOON, LocalTime.of(13,0), grace,
                EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
    }
    private AttendanceRules.Result run(String in, String out) {
        return AttendanceRules.calculate(new AttendanceRules.Input(date, standard(5), in == null ? null : date.atTime(LocalTime.parse(in)), out == null ? null : date.atTime(LocalTime.parse(out)), false, true));
    }
    @Test void normalShiftSubtractsOnlyLunch() { var r=run("08:50","18:10"); assertThat(r.status()).isEqualTo("NORMAL"); assertThat(r.workMinutes()).isEqualTo(480); assertThat(r.breakMinutes()).isEqualTo(60); }
    @Test void graceBoundaryIsInclusive() { assertThat(run("09:05","18:00").lateMinutes()).isZero(); }
    @Test void beyondGraceCountsTotalDelayRoundedUp() { var r=run("09:05:01","18:00"); assertThat(r.lateMinutes()).isEqualTo(6); assertThat(r.workMinutes()).isEqualTo(474); }
    @Test void combinesLateAndEarly() { var r=run("09:12","17:50"); assertThat(r.status()).isEqualTo("LATE_AND_EARLY"); assertThat(r.workMinutes()).isEqualTo(458); assertThat(r.earlyMinutes()).isEqualTo(10); }
    @Test void partialShiftDoesNotSubtractWholeLunch() { var r=run("09:00","12:30"); assertThat(r.workMinutes()).isEqualTo(180); assertThat(r.breakMinutes()).isEqualTo(30); }
    @Test void shiftBeforeLunchHasNoDeduction() { assertThat(run("09:00","11:00").workMinutes()).isEqualTo(120); }
    @Test void attendanceOnlyDuringBreakHasZeroWork() { assertThat(run("12:10","12:50").workMinutes()).isZero(); }
    @Test void missingPunchDoesNotInventWork() { assertThat(run("09:00",null).status()).isEqualTo("MISSING_PUNCH"); assertThat(run(null,"18:00").workMinutes()).isZero(); }
    @Test void absentRequiresFinalization() { assertThat(run(null,null).status()).isEqualTo("ABSENT"); var r=AttendanceRules.calculate(new AttendanceRules.Input(date,standard(5),null,null,false,false)); assertThat(r.status()).isEqualTo("PENDING"); }
    @Test void holidaysAndWeekendsAreRestDays() { var r=AttendanceRules.calculate(new AttendanceRules.Input(date,standard(5),null,null,true,true)); assertThat(r.status()).isEqualTo("REST_DAY"); r=AttendanceRules.calculate(new AttendanceRules.Input(date.plusDays(6),standard(5),null,null,false,true)); assertThat(r.status()).isEqualTo("REST_DAY"); }
    @Test void explicitWorkdayCanIncludeSunday() { var rule=new AttendanceRules.Rule(LocalTime.of(9,0),LocalTime.of(18,0),null,null,0,Set.of(DayOfWeek.SUNDAY)); var r=AttendanceRules.calculate(new AttendanceRules.Input(date.plusDays(6),rule,null,null,false,true)); assertThat(r.status()).isEqualTo("ABSENT"); }
    @Test void overnightBelongsToShiftStartDate() { var rule=new AttendanceRules.Rule(LocalTime.of(22,0),LocalTime.of(6,0),LocalTime.of(2,0),LocalTime.of(3,0),0,Set.of(DayOfWeek.MONDAY)); var r=AttendanceRules.calculate(new AttendanceRules.Input(date,rule,date.atTime(22,0),date.plusDays(1).atTime(6,0),false,true)); assertThat(r.workMinutes()).isEqualTo(420); assertThat(r.overnight()).isTrue(); assertThat(r.status()).isEqualTo("NORMAL"); }
    @Test void noLunchSupportsContinuousShift() { var rule=new AttendanceRules.Rule(LocalTime.of(9,0),LocalTime.of(18,0),null,null,0,Set.of(DayOfWeek.MONDAY)); assertThat(AttendanceRules.calculate(new AttendanceRules.Input(date,rule,date.atTime(9,0),date.atTime(18,0),false,true)).workMinutes()).isEqualTo(540); }
    @Test void rejectReversedEqualAndDistantEvents() { assertThatThrownBy(()->run("10:00","09:00")).isInstanceOf(IllegalArgumentException.class); assertThatThrownBy(()->run("09:00","09:00")).isInstanceOf(IllegalArgumentException.class); assertThatThrownBy(()->AttendanceRules.calculate(new AttendanceRules.Input(date,standard(5),date.minusDays(2).atTime(9,0),date.atTime(18,0),false,true))).isInstanceOf(IllegalArgumentException.class); }
    @Test void invalidBreakAndGraceAreRejected() { assertThatThrownBy(()->new AttendanceRules.Rule(LocalTime.of(9,0),LocalTime.of(18,0),LocalTime.of(19,0),LocalTime.of(20,0),0,Set.of())).isInstanceOf(IllegalArgumentException.class); assertThatThrownBy(()->standard(-1)).isInstanceOf(IllegalArgumentException.class); assertThatThrownBy(()->standard(121)).isInstanceOf(IllegalArgumentException.class); assertThatThrownBy(()->new AttendanceRules.Rule(LocalTime.of(9,0),LocalTime.of(18,0),LocalTime.NOON,null,0,Set.of())).isInstanceOf(IllegalArgumentException.class); }
}
