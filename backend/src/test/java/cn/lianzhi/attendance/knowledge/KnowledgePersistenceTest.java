package cn.lianzhi.attendance.knowledge;

import cn.lianzhi.attendance.knowledge.storage.*;
import cn.lianzhi.attendance.knowledge.retrieval.*;
import cn.lianzhi.attendance.common.BusinessException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import java.io.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest(properties={"spring.datasource.url=${LIANZHI_C_TEST_URL:jdbc:h2:mem:c-knowledge-v6;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","LIANZHI_KNOWLEDGE_WORKER_ENABLED=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class KnowledgePersistenceTest {
    @Autowired KnowledgeDocuments documents;@Autowired IsolatedParser isolated;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
    Authentication employee=new UsernamePasswordAuthenticationToken("employee","",List.of()),hr=new UsernamePasswordAuthenticationToken("hr","",List.of()),admin=new UsernamePasswordAuthenticationToken("admin","",List.of());long company;
    @BeforeEach void prepare(){company=jdbc.queryForObject("SELECT company_id FROM sys_user WHERE username='employee'",Long.class);}
    @AfterEach void cleanup(){jdbc.update("DELETE FROM knowledge_chunk");jdbc.update("DELETE FROM knowledge_revision");jdbc.update("DELETE FROM knowledge_document");}
    static byte[] doc(String text)throws IOException{try(var word=new XWPFDocument();var out=new ByteArrayOutputStream()){word.createParagraph().createRun().setText(text);word.write(out);return out.toByteArray();}}
    static byte[] image()throws IOException{try(var out=new ByteArrayOutputStream()){javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(8,8,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}}
    KnowledgeDocuments.Doc upload(String text)throws IOException{return documents.upload(employee,company,DocumentAccess.Visibility.PRIVATE,"制度.docx",doc(text));}
    @Test void actualChildProcessReturnsLocatedSections()throws Exception{var r=isolated.parse("制度.docx",doc("年假规定为五天。"));assertThat(r.code()).isEqualTo(cn.lianzhi.attendance.knowledge.parse.ParseCode.SUCCESS);assertThat(r.sections().get(0).text()).contains("年假规定");}
    @Test void uploadQueuePersistsAndParsedTextHasCurrentCitations()throws Exception{
        var d=upload("年假规定为五天。");assertThat(d.processing()).isEqualTo("QUEUED");assertThat(jdbc.queryForObject("SELECT original_bytes FROM knowledge_document WHERE id=?",byte[].class,d.id().toString())).isEqualTo(documents.original(employee,d.id()).bytes());assertThat(documents.processNext()).isTrue();
        var parsed=documents.get(employee,d.id());assertThat(parsed.processing()).isEqualTo("PARSED");assertThat(parsed.indexing()).isEqualTo("UNAVAILABLE");var hits=documents.search(employee,"年假",KnowledgeWorkspace.Mode.TEXT,10,0);assertThat(hits).hasSize(1);var c=hits.get(0).citation();assertThat(documents.cite(employee,d.id(),1,c.chunk().sequence())).isEqualTo(c);assertThat(documents.revisions(employee,d.id())).extracting(KnowledgeDocuments.Revision::action).containsExactly("UPLOAD");
    }
    @Test void companyAndPrivateScopesCannotBeForged()throws Exception{
        var d=upload("仅供本人阅读的制度。");assertThat(documents.get(hr,d.id()).id()).isEqualTo(d.id());
        String username="v6-other";jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,role,employee_no,company_id) VALUES(?,'test','同公司员工','EMPLOYEE',?,?)",username,username,company);
        try{var other=new UsernamePasswordAuthenticationToken(username,"",List.of());assertThatThrownBy(()->documents.get(other,d.id())).isInstanceOf(BusinessException.class);assertThatThrownBy(()->documents.upload(other,company,DocumentAccess.Visibility.COMPANY,"制度.docx",doc("公司制度"))).isInstanceOf(BusinessException.class);
            jdbc.update("UPDATE sys_user SET company_id=NULL WHERE username=?",username);assertThatThrownBy(()->documents.list(other,1,20)).isInstanceOf(BusinessException.class);
        }finally{jdbc.update("DELETE FROM sys_user WHERE username=?",username);}
    }
    @Test void replacementImmediatelyInvalidatesOldTextAndVersionThenDeletePurgesBytes()throws Exception{
        var d=upload("旧规定五天。");documents.processNext();var old=documents.search(employee,"旧规定",KnowledgeWorkspace.Mode.TEXT,10,0).get(0).citation();
        documents.replace(employee,d.id(),1,DocumentAccess.Visibility.PRIVATE,"制度.docx",doc("新规定七天。"));assertThat(documents.search(employee,"旧规定",KnowledgeWorkspace.Mode.TEXT,10,0)).isEmpty();assertThatThrownBy(()->documents.cite(employee,d.id(),1,old.chunk().sequence())).isInstanceOf(BusinessException.class);documents.processNext();assertThat(documents.search(employee,"新规定",KnowledgeWorkspace.Mode.TEXT,10,0)).hasSize(1);
        documents.delete(employee,d.id(),2);assertThat(documents.search(employee,"新规定",KnowledgeWorkspace.Mode.TEXT,10,0)).isEmpty();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT byte_size FROM knowledge_document",Long.class)).isZero();assertThatThrownBy(()->documents.original(employee,d.id())).isInstanceOf(BusinessException.class);
    }
    @Test void staleMutationsCannotOverwriteCurrentRevision()throws Exception{var d=upload("初版");documents.replace(employee,d.id(),1,DocumentAccess.Visibility.PRIVATE,"制度.docx",doc("第二版"));assertThatThrownBy(()->documents.delete(employee,d.id(),1)).isInstanceOf(BusinessException.class);assertThat(documents.get(employee,d.id()).version()).isEqualTo(2);}
    @Test void malformedUnsupportedAndMissingMediaServiceHaveExplicitStates()throws Exception{
        var bad=documents.upload(employee,company,DocumentAccess.Visibility.PRIVATE,"损坏.pdf",new byte[]{1,2,3});documents.processNext();assertThat(documents.get(employee,bad.id()).processing()).isEqualTo("FAILED");
        var image=documents.upload(employee,company,DocumentAccess.Visibility.PRIVATE,"图.png",image());documents.processNext();assertThat(documents.get(employee,image.id()).processing()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThatThrownBy(()->documents.search(employee,"制度",KnowledgeWorkspace.Mode.VECTOR,10,0)).isInstanceOf(BusinessException.class);
    }
    @Test void expiredLeaseFailsClosedAndCanBeExplicitlyRetried()throws Exception{var d=upload("规定");jdbc.update("UPDATE knowledge_document SET processing='PROCESSING',lease_token='lost',lease_until=0 WHERE id=?",d.id().toString());assertThat(documents.processNext()).isFalse();assertThat(documents.get(employee,d.id()).processing()).isEqualTo("FAILED");assertThat(documents.retry(employee,d.id(),1).version()).isEqualTo(2);documents.processNext();assertThat(documents.get(employee,d.id()).processing()).isEqualTo("PARSED");}
    @Test void filenameInputSizeAndPaginationAreBounded()throws Exception{assertThatThrownBy(()->documents.upload(employee,company,DocumentAccess.Visibility.PRIVATE,"../a.pdf",new byte[]{1})).isInstanceOf(BusinessException.class);assertThatThrownBy(()->documents.upload(employee,company,DocumentAccess.Visibility.PRIVATE,"a.pdf",new byte[0])).isInstanceOf(BusinessException.class);assertThatThrownBy(()->documents.list(employee,0,100)).isInstanceOf(BusinessException.class);}
    @Test void actualHttpUploadDownloadRequiresCsrfAndUsesServerOwner()throws Exception{
        var file=new MockMultipartFile("file","公开制度.docx","application/octet-stream",doc("公开制度"));
        mvc.perform(multipart("/api/knowledge/documents").file(file).param("companyId",Long.toString(company)).with(user("employee"))).andExpect(status().isForbidden());
        var r=mvc.perform(multipart("/api/knowledge/documents").file(file).param("companyId",Long.toString(company)).param("userId","1").with(user("employee")).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.data.processing").value("QUEUED")).andReturn();
        var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.getResponse().getContentAsString());String id=json.path("data").path("id").asText();long owner=jdbc.queryForObject("SELECT id FROM sys_user WHERE username='employee'",Long.class);assertThat(json.path("data").path("access").path("ownerUserId").asLong()).isEqualTo(owner);
        mvc.perform(get("/api/knowledge/documents/"+id+"/file").with(user("employee"))).andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options","nosniff"));
        mvc.perform(delete("/api/knowledge/documents/"+id).param("expectedVersion","1").with(user("employee"))).andExpect(status().isForbidden());
    }
    @Test void versionLimitStillAllowsPurgingTheFinalDocument()throws Exception{var d=upload("上限文档");jdbc.update("UPDATE knowledge_document SET version=50 WHERE id=?",d.id().toString());assertThatThrownBy(()->documents.replace(employee,d.id(),50,DocumentAccess.Visibility.PRIVATE,"新.docx",doc("新正文"))).isInstanceOf(BusinessException.class);documents.delete(employee,d.id(),50);assertThat(jdbc.queryForObject("SELECT original_bytes FROM knowledge_document WHERE id=?",byte[].class,d.id().toString())).isNull();}

}
