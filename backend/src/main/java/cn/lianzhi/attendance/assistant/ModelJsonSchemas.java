package cn.lianzhi.attendance.assistant;
import java.util.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;
import cn.lianzhi.attendance.common.BusinessException;

/** Generation constraints improve small-model syntax; all output still needs server validation. */
final class ModelJsonSchemas {
    private ModelJsonSchemas(){}
    private static Map<String,Object> object(Map<String,Object> properties){return Map.of("type","object","properties",properties,"required",List.copyOf(properties.keySet()),"additionalProperties",false);}
    static Map<String,Object> answer(){return object(Map.of("answerable",Map.of("type","boolean"),"answer",Map.of("type","string"),"citations",Map.of("type","array","maxItems",6,"items",object(Map.of("id",Map.of("type","string","enum",List.of("S1","S2","S3","S4","S5","S6")),"quote",Map.of("type","string"))))));}
    static String explicitType(String instruction){
        var types=new ArrayList<String>();
        if(instruction.contains("请假"))types.add("LEAVE");
        if(instruction.contains("加班"))types.add("OVERTIME");
        if(instruction.contains("补卡")||instruction.contains("补签"))types.add("MISSING_PUNCH");
        return types.size()==1?types.get(0):null;
    }
    record ExplicitTimes(String startAt,String endAt){}
    private static final Pattern ABSOLUTE_TIME=Pattern.compile("(?<!\\d)(\\d{4})(?:年|-)(\\d{1,2})(?:月|-)(\\d{1,2})日?[ T]*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?![\\d:])");
    static ExplicitTimes explicitTimes(String instruction,String type){
        if(type==null)return null;
        var matches=new ArrayList<java.util.regex.MatchResult>();var matcher=ABSOLUTE_TIME.matcher(instruction);
        while(matcher.find())matches.add(matcher.toMatchResult());
        boolean missing=type.equals("MISSING_PUNCH");
        if(missing&&matches.size()!=1)return null;
        if(!missing&&(matches.size()!=2||!instruction.substring(matches.get(0).end(),matches.get(1).start()).strip().matches("到|至|[-~～—－]")))return null;
        try{return new ExplicitTimes(timestamp(matches.get(0)),missing?null:timestamp(matches.get(1)));}
        catch(java.time.DateTimeException e){throw BusinessException.invalid("申请描述中的日期或时间无效，请核对");}
    }
    private static String timestamp(java.util.regex.MatchResult m){
        return LocalDateTime.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)),
            Integer.parseInt(m.group(4)),Integer.parseInt(m.group(5)),m.group(6)==null?0:Integer.parseInt(m.group(6)))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    }
    static Map<String,Object> suggestion(String explicitType,ExplicitTimes times){
        // Generation must not choose an endAt=null branch for LEAVE/OVERTIME.
        // Business validation remains authoritative even when a provider ignores the schema.
        var alternatives=new ArrayList<Map<String,Object>>();
        if(explicitType==null)for(String type:List.of("LEAVE","OVERTIME","MISSING_PUNCH"))alternatives.add(draft(type,null));
        else alternatives.add(draft(explicitType,times));
        alternatives.add(Map.of("type","null"));
        var draft=Map.of("anyOf",alternatives);
        return object(Map.of("ready",Map.of("type","boolean"),"message",Map.of("type","string"),"draft",draft));
    }
    private static Map<String,Object> draft(String type,ExplicitTimes times){
        var timestamp=Map.of("type","string","pattern","^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}$");
        var nothing=Map.of("type","null");boolean missing=type.equals("MISSING_PUNCH");
        var start=times==null?timestamp:Map.of("type","string","enum",List.of(times.startAt()));
        var end=times==null||times.endAt()==null?timestamp:Map.of("type","string","enum",List.of(times.endAt()));
        return object(Map.of("type",Map.of("type","string","enum",List.of(type)),"startAt",start,
                "endAt",missing?nothing:end,"punchType",missing?Map.of("type","string","enum",List.of("IN","OUT")):nothing,
                "workplaceId",missing?Map.of("type","integer","minimum",1):nothing,"reason",Map.of("type","string","minLength",1)));
    }
}
