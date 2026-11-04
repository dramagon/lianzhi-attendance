package cn.lianzhi.attendance.knowledge;
import cn.lianzhi.attendance.knowledge.storage.*;
import cn.lianzhi.attendance.knowledge.retrieval.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(properties="LIANZHI_KNOWLEDGE_WORKER_ENABLED=false") @ActiveProfiles("test")
class KnowledgeMysqlRestartIT {
    @Autowired KnowledgeDocuments documents;@Autowired JdbcTemplate jdbc;
    @Test void freshJvmSeesCommittedDocumentAndSourcesAfterRealMysqlRestart()throws Exception{
        try(var connection=jdbc.getDataSource().getConnection()){assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");assertThat(connection.getCatalog()).isEqualTo("c_verify");}
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success=TRUE ORDER BY installed_rank",String.class)).containsExactly("1","2","3","4","4.1","5","6");
        var actor=new UsernamePasswordAuthenticationToken("employee","",List.of());
        if("seed".equals(System.getenv("LIANZHI_C_RESTART_PHASE"))){long company=jdbc.queryForObject("SELECT company_id FROM sys_user WHERE username='employee'",Long.class);documents.upload(actor,company,DocumentAccess.Visibility.PRIVATE,"重启验证.docx",KnowledgePersistenceTest.doc("重启资料：必须保留原文件、切片、权限及来源。"));assertThat(documents.processNext()).isTrue();}
        var hits=documents.search(actor,"重启资料",KnowledgeWorkspace.Mode.TEXT,10,0);assertThat(hits).hasSize(1);var c=hits.get(0).citation();assertThat(documents.cite(actor,c.documentId(),c.version(),c.chunk().sequence())).isEqualTo(c);assertThat(documents.original(actor,c.documentId()).bytes()).isNotEmpty();assertThat(documents.revisions(actor,c.documentId())).hasSize(1);
    }
}
