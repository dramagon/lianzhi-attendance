package cn.lianzhi.attendance.assistant;
import cn.lianzhi.attendance.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:c-suggestion;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","LIANZHI_KNOWLEDGE_WORKER_ENABLED=false"}) @ActiveProfiles("test")
class ApprovalDraftSuggestionsTest {
    static final ObjectMapper JSON=new ObjectMapper();static final AtomicReference<Map<String,Object>> output=new AtomicReference<>();static final HttpServer SERVER;
    static{try{SERVER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);SERVER.createContext("/api/chat",x->{try{x.getRequestBody().readAllBytes();var result=Map.of("model","suggestion-fixture","done",true,"message",Map.of("role","assistant","content",JSON.writeValueAsString(output.get())));byte[] bytes=JSON.writeValueAsBytes(result);x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);x.close();}catch(Exception e){x.close();}});SERVER.start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("LIANZHI_AI_BASE_URL",()->"http://127.0.0.1:"+SERVER.getAddress().getPort());r.add("LIANZHI_AI_MODEL",()->"suggestion-fixture");}
    @Autowired ApprovalDraftSuggestions suggestions;@Autowired JdbcTemplate jdbc;
    @AfterAll static void stop(){SERVER.stop(0);}
    Map<String,Object> draft(){String date=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).toString();var d=new HashMap<String,Object>();d.put("type","LEAVE");d.put("startAt",date+"T09:00:00");d.put("endAt",date+"T18:00:00");d.put("punchType",null);d.put("workplaceId",null);d.put("reason","合成验证申请");return d;}
    @Test void modelSuggestionIsNormallyValidatedButCreatesNoApplication(){long before=jdbc.queryForObject("SELECT COUNT(*) FROM approval_request",Long.class);output.set(Map.of("ready",true,"message","请核对","draft",draft()));var result=suggestions.suggest(new UsernamePasswordAuthenticationToken("employee","",List.of()),"明天请假办理证件");assertThat(result.ready()).isTrue();assertThat(result.preview().stored()).isFalse();assertThat(result.preview().applicantId()).isEqualTo(jdbc.queryForObject("SELECT id FROM sys_user WHERE username='employee'",Long.class));assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_request",Long.class)).isEqualTo(before);}
    @Test void missingDetailsReturnNoDraft(){output.set(Map.of("ready",false,"message","请补充开始和结束时间"));var result=suggestions.suggest(new UsernamePasswordAuthenticationToken("employee","",List.of()),"我要请假");assertThat(result.ready()).isFalse();assertThat(result.preview()).isNull();}
    @Test void modelCannotForgeIdentityOrToolActions(){var d=draft();d.put("userId",1);output.set(Map.of("ready",true,"message","已提交","draft",d));assertThatThrownBy(()->suggestions.suggest(new UsernamePasswordAuthenticationToken("employee","",List.of()),"请假")).isInstanceOf(BusinessException.class).hasMessageContaining("格式无效");output.set(Map.of("ready",true,"message","提交","draft",draft(),"tool","submit"));assertThatThrownBy(()->suggestions.suggest(new UsernamePasswordAuthenticationToken("employee","",List.of()),"请假")).isInstanceOf(BusinessException.class);}
    @Test void normalABindingRulesRejectModelInventedWorkplace(){var d=draft();d.put("type","MISSING_PUNCH");d.put("startAt",LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1)+"T09:00:00");d.put("endAt",null);d.put("punchType","IN");d.put("workplaceId",999999);output.set(Map.of("ready",true,"message","补卡","draft",d));assertThatThrownBy(()->suggestions.suggest(new UsernamePasswordAuthenticationToken("employee","",List.of()),"补卡")).isInstanceOf(BusinessException.class).hasMessageContaining("本人当前有效绑定");}
}
