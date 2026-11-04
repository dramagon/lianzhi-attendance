package cn.lianzhi.attendance.assistant;
import java.util.*;

/** Generation constraints improve small-model syntax; all output still needs server validation. */
final class ModelJsonSchemas {
    private ModelJsonSchemas(){}
    private static Map<String,Object> object(Map<String,Object> properties){return Map.of("type","object","properties",properties,"required",List.copyOf(properties.keySet()),"additionalProperties",false);}
    static Map<String,Object> answer(){return object(Map.of("answerable",Map.of("type","boolean"),"answer",Map.of("type","string"),"citations",Map.of("type","array","maxItems",6,"items",object(Map.of("id",Map.of("type","string","enum",List.of("S1","S2","S3","S4","S5","S6")),"quote",Map.of("type","string"))))));}
    static Map<String,Object> suggestion(){
        var nullable=Map.of("type",List.of("string","null"));
        var draft=new HashMap<>(object(Map.of("type",Map.of("type","string","enum",List.of("LEAVE","OVERTIME","MISSING_PUNCH")),"startAt",Map.of("type","string"),"endAt",nullable,"punchType",Map.of("type",List.of("string","null"),"enum",Arrays.asList("IN","OUT",null)),"workplaceId",Map.of("type",List.of("integer","null")),"reason",Map.of("type","string"))));
        draft.put("type",List.of("object","null"));
        return object(Map.of("ready",Map.of("type","boolean"),"message",Map.of("type","string"),"draft",draft));
    }
}
