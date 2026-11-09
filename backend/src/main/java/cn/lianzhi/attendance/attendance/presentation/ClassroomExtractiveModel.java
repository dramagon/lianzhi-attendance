package cn.lianzhi.attendance.attendance.presentation;

import cn.lianzhi.attendance.assistant.CitedModel;
import cn.lianzhi.attendance.knowledge.provider.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** Offline source excerpts only. Retrieval, permissions and quote verification remain in CitedAnswerService. */
@Component @Primary @Profile("classroom")
@ConditionalOnProperty(name="lianzhi.demo",havingValue="true")
public class ClassroomExtractiveModel implements CitedModel {
    @Override public String mode() { return "EXTRACTIVE"; }
    @Override public ProviderAvailability availability() { return ProviderAvailability.AVAILABLE; }
    @Override public ProviderResult<Draft> generate(String question,List<Evidence> evidence) {
        var quotes=evidence.stream().filter(item->item.text()!=null&&!item.text().isBlank())
            .limit(2).map(item->new Quote(item.id(),excerpt(item.text()))).toList();
        if(quotes.isEmpty()) return new ProviderResult<>(ProviderResult.State.SUCCESS,"没有可摘录的原文",
            new Draft(false,"没有找到可用依据",List.of()));
        String answer="离线原文摘录（非生成式AI；请结合完整制度核对）：\n"+
            String.join("\n\n",quotes.stream().map(q->"["+q.id()+"] "+q.quote()).toList());
        return new ProviderResult<>(ProviderResult.State.SUCCESS,"仅摘录实际检索结果，未调用外部模型",
            new Draft(true,answer,quotes));
    }
    private String excerpt(String value) {
        return value.substring(0,value.offsetByCodePoints(0,Math.min(800,value.codePointCount(0,value.length()))));
    }
}
