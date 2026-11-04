package cn.lianzhi.attendance.report;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Small transparent baseline, evaluated on a chronological holdout; never a staffing prediction. */
public final class WorkForecast {
    private WorkForecast(){}
    public record Point(LocalDate date,double minutesPerExpectedDay){}
    public record Forecast(String state,String method,int samples,int validationDays,Double mae,Double meanBaselineMae,List<Point> points,String note){}
    public static Forecast calculate(List<AttendanceReports.Trend> trend){
        var history=trend.stream().filter(d->d.totals().expectedDays()>0&&d.totals().pendingDays()==0).sorted(Comparator.comparing(AttendanceReports.Trend::date)).toList();
        String note="仅参考已结束班次的每应出勤人天有效分钟，不预测人数、总工时或个体表现；未来排班及制度变化会使预测失效。";
        if(history.size()<14)return new Forecast("INSUFFICIENT_DATA","NONE",history.size(),0,null,null,List.of(),"至少需要14个有效业务日。"+note);
        int holdout=Math.max(3,history.size()/5),train=history.size()-holdout;LocalDate first=history.get(0).date();double[] fit=fit(history.subList(0,train),first);double mean=history.subList(0,train).stream().mapToDouble(WorkForecast::value).average().orElseThrow();double error=0,baseline=0;
        for(var d:history.subList(train,history.size())){double x=ChronoUnit.DAYS.between(first,d.date());error+=Math.abs(value(d)-clamp(fit[0]+fit[1]*x));baseline+=Math.abs(value(d)-mean);}
        error/=holdout;baseline/=holdout;boolean linear=error<baseline;double[] full=fit(history,first);double fullMean=history.stream().mapToDouble(WorkForecast::value).average().orElseThrow();var points=new ArrayList<Point>();LocalDate last=history.get(history.size()-1).date();
        for(int i=1;i<=7;i++){LocalDate date=last.plusDays(i);points.add(new Point(date,round(clamp(linear?full[0]+full[1]*ChronoUnit.DAYS.between(first,date):fullMean))));}
        return new Forecast("REFERENCE_ONLY",linear?"LINEAR_REGRESSION":"HISTORICAL_MEAN",history.size(),holdout,round(linear?error:baseline),round(baseline),List.copyOf(points),note);
    }
    private static double value(AttendanceReports.Trend d){return d.totals().workMinutes()/(double)d.totals().expectedDays();}
    private static double[] fit(List<AttendanceReports.Trend> data,LocalDate first){double x=0,y=0,xx=0,xy=0;for(var d:data){double a=ChronoUnit.DAYS.between(first,d.date()),b=value(d);x+=a;y+=b;xx+=a*a;xy+=a*b;}double denominator=data.size()*xx-x*x;double slope=denominator==0?0:(data.size()*xy-x*y)/denominator;return new double[]{(y-slope*x)/data.size(),slope};}
    private static double clamp(double n){return Math.min(1440,Math.max(0,n));}
    private static double round(double n){return Math.round(n*100)/100.0;}
}
