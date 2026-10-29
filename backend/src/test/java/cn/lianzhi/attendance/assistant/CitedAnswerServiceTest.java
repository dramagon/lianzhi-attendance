package cn.lianzhi.attendance.assistant;

import cn.lianzhi.attendance.knowledge.parse.DocumentParser;
import cn.lianzhi.attendance.knowledge.provider.*;
import cn.lianzhi.attendance.knowledge.retrieval.*;
import cn.lianzhi.attendance.knowledge.retrieval.KnowledgeWorkspace.Citation;
import cn.lianzhi.attendance.knowledge.text.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class CitedAnswerServiceTest {
    final Authentication actor=UsernamePasswordAuthenticationToken.authenticated("employee","unused",List.of());
    final Citation citation=new Citation(UUID.randomUUID(),1,new TextChunk(1,1,SourceLocation.page(3),0,7,"请假须提前申请"));
    final AtomicInteger calls=new AtomicInteger();
    CitedModel model(CitedModel.Draft draft){return new CitedModel(){
        public ProviderAvailability availability(){return ProviderAvailability.AVAILABLE;}
        public ProviderResult<Draft> generate(String q,List<Evidence> e){calls.incrementAndGet();return new ProviderResult<>(ProviderResult.State.SUCCESS,"synthetic test response",draft);}
    };}
    CitedModel.Draft valid(){return new CitedModel.Draft(true,"请提前申请。",List.of(new CitedModel.Quote("S1","提前申请")));}
    CitedAnswerService.Sources sources(){return new CitedAnswerService.Sources(){
        public List<Citation> search(Authentication a,String q,int limit){return List.of(citation);}
        public Citation cite(Authentication a,UUID id,long version,int sequence){return citation;}
    };}
    void empty(CitedAnswerService.Answer answer,CitedAnswerService.State state){assertThat(answer.state()).isEqualTo(state);assertThat(answer.text()).isNull();assertThat(answer.references()).isEmpty();}
    @Test void validQuoteRetainsExactDocumentVersionAndPhysicalPage(){
        var answer=new CitedAnswerService(sources(),model(valid())).ask(actor,"如何请假？");
        assertThat(answer.state()).isEqualTo(CitedAnswerService.State.ANSWERED);
        assertThat(answer.references().get(0).source()).isEqualTo(citation);
        assertThat(answer.references().get(0).quote()).isEqualTo("提前申请");
        assertThat(calls).hasValue(1);
    }
    @Test void noEvidenceDoesNotCallModel(){
        var s=new CitedAnswerService.Sources(){public List<Citation> search(Authentication a,String q,int n){return List.of();}public Citation cite(Authentication a,UUID id,long v,int n){throw new AssertionError();}};
        empty(new CitedAnswerService(s,model(valid())).ask(actor,"问题"),CitedAnswerService.State.NO_EVIDENCE);
        assertThat(calls).hasValue(0);
    }
    @Test void unavailableModelNeverFabricatesAnswer(){
        empty(new CitedAnswerService(sources(),new OllamaCitedModel("","")).ask(actor,"问题"),CitedAnswerService.State.NOT_CONFIGURED);
    }
    @Test void unknownOrEmptyOrInventedQuotesAreRejected(){
        for(var quote:List.of(new CitedModel.Quote("S9","提前申请"),new CitedModel.Quote("S1",""),new CitedModel.Quote("S1","公司自动批准"))){
            empty(new CitedAnswerService(sources(),model(new CitedModel.Draft(true,"答案",List.of(quote)))).ask(actor,"问题"),CitedAnswerService.State.INVALID_RESPONSE);
        }
    }
    @Test void answerWithoutCitationsIsRejected(){
        empty(new CitedAnswerService(sources(),model(new CitedModel.Draft(true,"答案",List.of()))).ask(actor,"问题"),CitedAnswerService.State.INVALID_RESPONSE);
    }
    @Test void modelRefusalDoesNotExposeUntrustedAnswerText(){
        empty(new CitedAnswerService(sources(),model(new CitedModel.Draft(false,"untrusted",List.of()))).ask(actor,"问题"),CitedAnswerService.State.NO_EVIDENCE);
    }
    @Test void removedBeforeGenerationPreventsDisclosureToModel(){
        var s=new CitedAnswerService.Sources(){public List<Citation> search(Authentication a,String q,int n){return List.of(citation);}public Citation cite(Authentication a,UUID id,long v,int n){throw new IllegalStateException("SECRET");}};
        empty(new CitedAnswerService(s,model(valid())).ask(actor,"问题"),CitedAnswerService.State.STALE_SOURCES);assertThat(calls).hasValue(0);
    }
    @Test void revokedAfterGenerationDiscardsAnswer(){
        var count=new AtomicInteger();
        var s=new CitedAnswerService.Sources(){public List<Citation> search(Authentication a,String q,int n){return List.of(citation);}public Citation cite(Authentication a,UUID id,long v,int n){if(count.incrementAndGet()>1)throw new IllegalStateException("revoked");return citation;}};
        empty(new CitedAnswerService(s,model(valid())).ask(actor,"问题"),CitedAnswerService.State.STALE_SOURCES);
        assertThat(calls).hasValue(1);
    }
    @Test void changedChunkWithSameIdAndVersionIsRejected(){
        var changed=new Citation(citation.documentId(),1,new TextChunk(1,1,SourceLocation.page(3),0,3,"已变化"));
        var s=new CitedAnswerService.Sources(){public List<Citation> search(Authentication a,String q,int n){return List.of(citation);}public Citation cite(Authentication a,UUID id,long v,int n){return changed;}};
        empty(new CitedAnswerService(s,model(valid())).ask(actor,"问题"),CitedAnswerService.State.STALE_SOURCES);
    }
    @Test void errorsNeverReturnProviderDiagnosticOrPartialContent(){
        var broken=new CitedModel(){public ProviderAvailability availability(){return ProviderAvailability.AVAILABLE;}public ProviderResult<Draft> generate(String q,List<Evidence> e){throw new IllegalStateException("SECRET");}};
        var answer=new CitedAnswerService(sources(),broken).ask(actor,"问题");empty(answer,CitedAnswerService.State.UNAVAILABLE);assertThat(answer.message()).doesNotContain("SECRET");
    }
    @Test void invalidQuestionsAndAnonymousCallersAreRejectedBeforeSearch(){
        var service=new CitedAnswerService(sources(),model(valid()));
        assertThatThrownBy(()->service.ask(null,"问题")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->service.ask(actor," ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.ask(actor,"问".repeat(1001))).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
    }
    @Test void realPdfThroughWorkspaceToCitedAnswerPreservesSource() throws Exception {
        var w=new KnowledgeWorkspace(new DocumentParser(),new TextChunker(),new OllamaEmbeddingProvider("",""),WorkspaceLimits.defaults());
        var user=new KnowledgeActor(3,KnowledgeActor.Role.EMPLOYEE,10L);
        byte[] bytes;try(var in=getClass().getResourceAsStream("/knowledge/unicode-three-pages.pdf")){bytes=in.readAllBytes();}
        var d=w.upload(user,10,DocumentAccess.Visibility.PRIVATE,"合成.pdf",bytes);
        var s=new CitedAnswerService.Sources(){
            public List<Citation> search(Authentication a,String q,int n){return w.search(user,"第三页",KnowledgeWorkspace.Mode.TEXT,n,0).stream().map(KnowledgeWorkspace.Hit::citation).toList();}
            public Citation cite(Authentication a,UUID id,long v,int seq){return w.cite(user,id,v,seq);}
        };
        var answer=new CitedAnswerService(s,model(new CitedModel.Draft(true,"审批后更新考勤",List.of(new CitedModel.Quote("S1","请假审批完成后更新考勤"))))).ask(actor,"审批后怎么办？");
        assertThat(answer.state()).isEqualTo(CitedAnswerService.State.ANSWERED);
        assertThat(answer.references().get(0).source().documentId()).isEqualTo(d.id());
        assertThat(answer.references().get(0).source().chunk().source()).isEqualTo(SourceLocation.page(3));
    }
}
