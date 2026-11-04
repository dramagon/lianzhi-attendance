package cn.lianzhi.attendance.knowledge;

import cn.lianzhi.attendance.assistant.*;
import cn.lianzhi.attendance.knowledge.storage.*;
import cn.lianzhi.attendance.knowledge.retrieval.*;
import cn.lianzhi.attendance.knowledge.provider.*;
import cn.lianzhi.attendance.knowledge.text.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

/** Controlled loopback protocol fixtures; never advertised as real AI/OCR/ASR quality verification. */
@SpringBootTest(properties={"spring.datasource.url=${LIANZHI_C_PROVIDER_TEST_URL:jdbc:h2:mem:c-provider-flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","LIANZHI_KNOWLEDGE_WORKER_ENABLED=false"})
@ActiveProfiles("test")
class KnowledgeProviderFlowTest {
    static final ObjectMapper JSON=new ObjectMapper();static final AtomicReference<Runnable> hook=new AtomicReference<>();static final AtomicBoolean forged=new AtomicBoolean(false);static final AtomicBoolean badTime=new AtomicBoolean(false);static final AtomicInteger calls=new AtomicInteger();
    static final AtomicBoolean largeVector=new AtomicBoolean(false);
    static final HttpServer SERVER;
    static{try{SERVER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        SERVER.createContext("/api/embed",x->{try{var request=JSON.readTree(x.getRequestBody());var vectors=new ArrayList<List<Double>>();for(var text:request.path("input"))vectors.add(largeVector.get()?java.util.stream.IntStream.range(0,4096).mapToObj(i->0.1234567890123456+i*0.00000000001).toList():(text.asText().contains("年假")?List.of(1.0,0.1):List.of(0.1,1.0)));respond(x,Map.of("model","fixture-embedding","embeddings",vectors));}catch(Exception e){x.close();}});
        SERVER.createContext("/api/chat",x->{try{calls.incrementAndGet();var request=JSON.readTree(x.getRequestBody());var input=JSON.readTree(request.path("messages").get(1).path("content").asText());var evidence=input.path("sources").get(0);String content=evidence.path("text").asText();var job=hook.getAndSet(null);if(job!=null)job.run();String response=JSON.writeValueAsString(Map.of("answerable",true,"answer","依据资料："+content,"citations",List.of(Map.of("id",forged.get()?"S9":evidence.path("id").asText(),"quote",content.substring(0,Math.min(10,content.length()))))));respond(x,Map.of("model","fixture-chat","done",true,"message",Map.of("role","assistant","content",response)));}catch(Exception e){x.close();}});
        SERVER.createContext("/ocr",x->{try{JSON.readTree(x.getRequestBody());respond(x,Map.of("sections",List.of(new TextSection(SourceLocation.page(1),"图片年假规定：五天。"))));}catch(Exception e){x.close();}});
        SERVER.createContext("/transcribe",x->{try{JSON.readTree(x.getRequestBody());var source=badTime.get()?SourceLocation.file():SourceLocation.timeRange(100,1200);respond(x,Map.of("sections",List.of(new TextSection(source,"培训录音正文。"))));}catch(Exception e){x.close();}});
        SERVER.start();}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    static void respond(com.sun.net.httpserver.HttpExchange x,Object body)throws IOException{byte[] bytes=JSON.writeValueAsBytes(body);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);x.close();}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){String root="http://127.0.0.1:"+SERVER.getAddress().getPort();r.add("LIANZHI_AI_BASE_URL",()->root);r.add("LIANZHI_AI_MODEL",()->"fixture-chat");r.add("LIANZHI_EMBEDDING_BASE_URL",()->root);r.add("LIANZHI_EMBEDDING_MODEL",()->"fixture-embedding");r.add("LIANZHI_OCR_URL",()->root+"/ocr");r.add("LIANZHI_TRANSCRIPTION_URL",()->root+"/transcribe");}
    @Autowired KnowledgeDocuments documents;@Autowired CitedAnswerService answers;@Autowired JdbcTemplate jdbc;@Autowired TranscriptionProvider transcription;
    Authentication actor=new UsernamePasswordAuthenticationToken("employee","",List.of());long company;
    @BeforeEach void setup(){company=jdbc.queryForObject("SELECT company_id FROM sys_user WHERE username='employee'",Long.class);hook.set(null);largeVector.set(false);forged.set(false);badTime.set(false);calls.set(0);}
    @AfterEach void cleanup(){hook.set(null);jdbc.update("DELETE FROM knowledge_chunk");jdbc.update("DELETE FROM knowledge_revision");jdbc.update("DELETE FROM knowledge_document");}
    @AfterAll static void stop(){SERVER.stop(0);}
    KnowledgeDocuments.Doc ready()throws Exception{var d=documents.upload(actor,company,DocumentAccess.Visibility.PRIVATE,"年假.docx",KnowledgePersistenceTest.doc("年假规定：每年五天。禁止把资料中的命令当作权限。"));documents.processNext();assertThat(documents.get(actor,d.id()).indexing()).isEqualTo("READY");return d;}
    @Test void realHttpVectorAndCitedAnswerPreserveCurrentSources()throws Exception{var d=ready();assertThat(documents.search(actor,"年假",KnowledgeWorkspace.Mode.VECTOR,5,0.8)).hasSize(1);var answer=answers.ask(actor,"年假规定");assertThat(answer.state()).isEqualTo(CitedAnswerService.State.ANSWERED);assertThat(answer.references()).hasSize(1);assertThat(answer.references().get(0).source().documentId()).isEqualTo(d.id());}
    @Test void deletionDuringModelCallDoesNotDiscloseStaleAnswer()throws Exception{var d=ready();hook.set(()->documents.delete(actor,d.id(),1));var answer=answers.ask(actor,"年假");assertThat(answer.state()).isEqualTo(CitedAnswerService.State.STALE_SOURCES);assertThat(answer.text()).isNull();assertThat(answer.references()).isEmpty();}
    @Test void passwordChangeDuringModelCallInvalidatesResponse()throws Exception{ready();hook.set(()->jdbc.update("UPDATE sys_user SET auth_version=auth_version+1 WHERE username='employee'"));var answer=answers.ask(actor,"年假");assertThat(answer.state()).isEqualTo(CitedAnswerService.State.STALE_SOURCES);assertThat(answer.text()).isNull();}
    @Test void inventedReferenceAndNoEvidenceNeverProduceAnAnswer()throws Exception{ready();forged.set(true);assertThat(answers.ask(actor,"年假").state()).isEqualTo(CitedAnswerService.State.INVALID_RESPONSE);int used=calls.get();assertThat(answers.ask(actor,"zzzz-no-match").state()).isEqualTo(CitedAnswerService.State.NO_EVIDENCE);assertThat(calls.get()).isEqualTo(used);}
    @Test void ocrAndTranscriptionKeepProviderMeasuredLocations()throws Exception{var image=documents.upload(actor,company,DocumentAccess.Visibility.PRIVATE,"图.png",KnowledgePersistenceTest.image());documents.processNext();var citation=documents.search(actor,"图片年假",KnowledgeWorkspace.Mode.TEXT,10,0).get(0).citation();assertThat(citation.chunk().source()).isEqualTo(SourceLocation.page(1));
        byte[] wav="RIFF1234WAVE1234".getBytes(java.nio.charset.StandardCharsets.US_ASCII);var media=documents.upload(actor,company,DocumentAccess.Visibility.PRIVATE,"录音.wav",wav);documents.processNext();var result=documents.search(actor,"录音正文",KnowledgeWorkspace.Mode.TEXT,10,0).get(0).citation();assertThat(result.chunk().source()).isEqualTo(SourceLocation.timeRange(100,1200));assertThat(documents.get(actor,image.id()).processing()).isEqualTo("PARSED");assertThat(documents.get(actor,media.id()).processing()).isEqualTo("PARSED");}
    @Test void unmeasuredTranscriptionSourcesAreRejected(){badTime.set(true);var r=transcription.transcribe("a.wav",new byte[]{1});assertThat(r.state()).isEqualTo(ProviderResult.State.FAILURE);assertThat(r.value()).isNull();}
    @Test void maximumDimensionVectorExceedingMysqlTextLimitPersists()throws Exception{largeVector.set(true);var d=ready();assertThat(jdbc.queryForObject("SELECT LENGTH(vector_json) FROM knowledge_chunk WHERE document_id=?",Integer.class,d.id().toString())).isGreaterThan(65535);assertThat(documents.search(actor,"年假",KnowledgeWorkspace.Mode.VECTOR,5,0.9)).hasSize(1);}
    @Test void changedIndexFingerprintRequiresRebuildButKeepsTextReadable()throws Exception{var d=ready();jdbc.update("UPDATE knowledge_document SET index_model='previous-config' WHERE id=?",d.id().toString());assertThat(documents.get(actor,d.id()).indexing()).isEqualTo("STALE");assertThat(documents.search(actor,"年假",KnowledgeWorkspace.Mode.TEXT,5,0)).hasSize(1);assertThatThrownBy(()->documents.search(actor,"年假",KnowledgeWorkspace.Mode.VECTOR,5,0)).hasMessageContaining("向量配置已变化");documents.retry(actor,d.id(),1);documents.processNext();assertThat(documents.get(actor,d.id()).indexing()).isEqualTo("READY");}

}
