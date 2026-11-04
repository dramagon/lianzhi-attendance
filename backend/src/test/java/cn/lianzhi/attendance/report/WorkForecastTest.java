package cn.lianzhi.attendance.report;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class WorkForecastTest {
    List<AttendanceReports.Trend> history(int count,boolean linear){var list=new ArrayList<AttendanceReports.Trend>();for(int i=0;i<count;i++)list.add(new AttendanceReports.Trend(LocalDate.of(2026,1,1).plusDays(i),new AttendanceReports.Totals(2,0,2,2,null,0,0,0,0,0,0,(linear?300+i*2:480)*2,0,0)));return list;}
    @Test void insufficientDataProducesNoInventedForecast(){assertThat(WorkForecast.calculate(history(13,false)).points()).isEmpty();assertThat(WorkForecast.calculate(history(13,false)).state()).isEqualTo("INSUFFICIENT_DATA");}
    @Test void chronologicalHoldoutSelectsLinearWhenItBeatsMean(){var f=WorkForecast.calculate(history(20,true));assertThat(f.method()).isEqualTo("LINEAR_REGRESSION");assertThat(f.mae()).isZero();assertThat(f.validationDays()).isEqualTo(4);assertThat(f.points()).hasSize(7);assertThat(f.points().get(0).minutesPerExpectedDay()).isEqualTo(340);}
    @Test void constantHistoryUsesMeanAndDoesNotPretendToPredictHeadcount(){var f=WorkForecast.calculate(history(20,false));assertThat(f.method()).isEqualTo("HISTORICAL_MEAN");assertThat(f.mae()).isZero();assertThat(f.points()).allMatch(p->p.minutesPerExpectedDay()==480);assertThat(f.note()).contains("不预测人数");}
}
