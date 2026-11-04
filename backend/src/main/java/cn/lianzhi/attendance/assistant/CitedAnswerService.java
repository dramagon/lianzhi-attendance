package cn.lianzhi.attendance.assistant;

import cn.lianzhi.attendance.knowledge.provider.*;
import cn.lianzhi.attendance.knowledge.retrieval.KnowledgeWorkspace.Citation;
import cn.lianzhi.attendance.knowledge.text.TextNormalizer;
import org.springframework.security.core.Authentication;
import java.util.*;

/** Grounded RAG policy wired to the durable authorized source store. */
public final class CitedAnswerService {
    public interface Sources {
        default Object identity(Authentication actor){return actor.getName();}
        List<Citation> search(Authentication actor,String question,int limit);
        Citation cite(Authentication actor,UUID documentId,long version,int sequence);
    }
    public enum State { ANSWERED, NO_EVIDENCE, NOT_CONFIGURED, UNAVAILABLE, INVALID_RESPONSE, STALE_SOURCES }
    public record Reference(String id,Citation source,String quote) {}
    public record Answer(State state,String message,String text,List<Reference> references) {
        public Answer { references=List.copyOf(references); }
    }
    private final Sources sources;
    private final CitedModel model;
    public CitedAnswerService(Sources sources,CitedModel model) { this.sources=Objects.requireNonNull(sources);this.model=Objects.requireNonNull(model); }
    public Answer ask(Authentication actor,String question) {
        if(actor==null || !actor.isAuthenticated() || actor instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)
            throw new org.springframework.security.access.AccessDeniedException("请先登录");
        if(question==null || question.length()>4000) throw new IllegalArgumentException("问题需为1～1000字");
        question=TextNormalizer.normalize(question);
        if(question.isBlank() || question.codePointCount(0,question.length())>1000)throw new IllegalArgumentException("问题需为1～1000字");
        Object identity=sources.identity(actor);
        List<Citation> found;
        try { found=List.copyOf(sources.search(actor,question,6)); }
        catch(RuntimeException e) { return failure(State.UNAVAILABLE,"知识检索暂不可用"); }
        if(found.isEmpty())return failure(State.NO_EVIDENCE,"没有找到有权访问的依据，暂不能回答");
        if(found.size()>6)return failure(State.INVALID_RESPONSE,"检索结果超出允许范围");
        var evidence=new ArrayList<CitedModel.Evidence>();var offered=new LinkedHashMap<String,Citation>();
        try {
            int index=0;
            for(var citation:found) {
                var current=reload(actor,citation);
                if(current.chunk().content().isBlank() || current.chunk().content().codePointCount(0,current.chunk().content().length())>800)
                    return failure(State.INVALID_RESPONSE,"来源正文不符合限制");
                if(offered.containsValue(current))continue;
                String id="S"+(++index);offered.put(id,current);evidence.add(new CitedModel.Evidence(id,current.chunk().content()));
            }
        }catch(RuntimeException e){return failure(State.STALE_SOURCES,"来源已更新、删除或不可访问，请重新查询");}
        ProviderResult<CitedModel.Draft> result;
        try {
            if(model.availability()==ProviderAvailability.NOT_CONFIGURED)return failure(State.NOT_CONFIGURED,"问答模型尚未配置");
            if(model.availability()!=ProviderAvailability.AVAILABLE)return failure(State.UNAVAILABLE,"问答模型暂不可用");
            result=model.generate(question,List.copyOf(evidence));
        }catch(RuntimeException e){return failure(State.UNAVAILABLE,"问答服务暂不可用");}
        if(result==null || result.state()!=ProviderResult.State.SUCCESS)return failure(State.UNAVAILABLE,"问答服务暂不可用");
        var draft=result.value();
        if(!draft.answerable())return failure(State.NO_EVIDENCE,"现有资料不足以回答这个问题");
        if(draft.answer()==null || draft.answer().isBlank() || draft.answer().length()>6000 || draft.citations().isEmpty() || draft.citations().size()>6)
            return failure(State.INVALID_RESPONSE,"模型回答缺少有效来源");
        var references=new ArrayList<Reference>();var ids=new HashSet<String>();
        for(var quote:draft.citations()){
            var citation=offered.get(quote.id());
            if(citation==null || !ids.add(quote.id()) || quote.quote()==null || quote.quote().isBlank()
                    || quote.quote().length()>1600 || !citation.chunk().content().contains(quote.quote()))
                return failure(State.INVALID_RESPONSE,"模型引用与原文不一致");
            references.add(new Reference(quote.id(),citation,quote.quote()));
        }
        // Revalidate EVERY disclosed source after provider I/O, including offered but unquoted text.
        // The source port is responsible for checking current DB identity, ACL and version each time.
        try { for(var citation:offered.values())reload(actor,citation);if(!Objects.equals(identity,sources.identity(actor)))throw new IllegalStateException("Identity changed"); }
        catch(RuntimeException e){return failure(State.STALE_SOURCES,"来源已更新、删除或不可访问，请重新查询");}
        return new Answer(State.ANSWERED,"回答已生成，请结合来源核对",draft.answer(),references);
    }
    private Citation reload(Authentication actor,Citation citation) {
        var current=sources.cite(actor,citation.documentId(),citation.version(),citation.chunk().sequence());
        if(!citation.equals(current))throw new IllegalStateException("Source changed");
        return current;
    }
    private static Answer failure(State state,String message){return new Answer(state,message,null,List.of());}
}
