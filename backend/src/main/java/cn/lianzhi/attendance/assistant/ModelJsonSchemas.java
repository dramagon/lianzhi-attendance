package cn.lianzhi.attendance.assistant;
import java.util.*;

/** Generation constraints improve small-model syntax; all output still needs server validation. */
final class ModelJsonSchemas {
    private ModelJsonSchemas(){}
    private static Map<String,Object> object(Map<String,Object> properties){return Map.of("type","object","properties",properties,"required",List.copyOf(properties.keySet()),"additionalProperties",false);}
    static Map<String,Object> answer(){return object(Map.of("answerable",Map.of("type","boolean"),"answer",Map.of("type","string"),"citations",Map.of("type","array","maxItems",6,"items",object(Map.of("id",Map.of("type","string","enum",List.of("S1","S2","S3","S4","S5","S6")),"quote",Map.of("type","string"))))));}
    static Map<String,Object> suggestion(){
        // Generation must not choose an endAt=null branch for LEAVE/OVERTIME.
        // Business validation remains authoritative even when a provider ignores the schema.
        var draft=Map.of("anyOf",List.of(draft("LEAVE"),draft("OVERTIME"),draft("MISSING_PUNCH"),Map.of("type","null")));
        return object(Map.of("ready",Map.of("type","boolean"),"message",Map.of("type","string"),"draft",draft));
    }
    private static Map<String,Object> draft(String type){
        var timestamp=Map.of("type","string","pattern","^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}$");
        var nothing=Map.of("type","null");boolean missing=type.equals("MISSING_PUNCH");
        return object(Map.of("type",Map.of("type","string","enum",List.of(type)),"startAt",timestamp,
                "endAt",missing?nothing:timestamp,"punchType",missing?Map.of("type","string","enum",List.of("IN","OUT")):nothing,
                "workplaceId",missing?Map.of("type","integer","minimum",1):nothing,"reason",Map.of("type","string","minLength",1)));
    }
}
