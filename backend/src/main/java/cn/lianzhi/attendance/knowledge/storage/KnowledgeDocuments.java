package cn.lianzhi.attendance.knowledge.storage;

import cn.lianzhi.attendance.assistant.CitedAnswerService;
import cn.lianzhi.attendance.common.*;
import cn.lianzhi.attendance.identity.AccountMapper;
import cn.lianzhi.attendance.knowledge.parse.*;
import cn.lianzhi.attendance.knowledge.provider.*;
import cn.lianzhi.attendance.knowledge.retrieval.*;
import cn.lianzhi.attendance.knowledge.text.*;
import cn.lianzhi.attendance.organization.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.*;
import java.sql.ResultSet;
import java.util.*;
import java.util.function.Supplier;
import static cn.lianzhi.attendance.knowledge.retrieval.KnowledgeWorkspace.*;

/** Durable bounded store. DB mutations serialize capacity checks; provider I/O holds no DB locks. */
@Service
public class KnowledgeDocuments implements CitedAnswerService.Sources {
    public record Doc(UUID id,long version,String name,DocumentAccess access,String sha256,long byteSize,String processing,String indexing,String message,int chunkCount,long createdAt,long updatedAt){}
    public record Revision(long version,long actorId,String action,String name,String sha256,long occurredAt){}
    public record Original(String name,byte[] bytes){}
    private record Stamp(KnowledgeActor actor,long authVersion){}
    private record Acl(String sql,List<Object> args){}
    private record Job(Doc doc,byte[] bytes,String token){}
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final KnowledgeIdentity identity;private final DataScope scope;private final OrganizationMapper organizations;private final AccountMapper accounts;
    private final EmbeddingProvider embeddings;private final DocumentContentProcessor processor;private final TransactionTemplate tx;private final boolean enabled;private final String modelKey;
    private final TextChunker chunker=new TextChunker(new ChunkingOptions(800,120,160_000,1000,200));
    private static final String COLUMNS="d.id,d.version,d.company_id,d.owner_user_id,d.visibility,d.name,d.sha256,d.byte_size,d.processing,d.indexing,d.message,d.chunk_count,d.created_at,d.updated_at,d.index_model";
    public KnowledgeDocuments(JdbcTemplate jdbc,ObjectMapper json,KnowledgeIdentity identity,DataScope scope,OrganizationMapper organizations,AccountMapper accounts,EmbeddingProvider embeddings,DocumentContentProcessor processor,PlatformTransactionManager manager,
        @Value("${LIANZHI_KNOWLEDGE_ENABLED:true}") boolean enabled,@Value("${LIANZHI_EMBEDDING_BASE_URL:}") String base,@Value("${LIANZHI_EMBEDDING_MODEL:}") String model,@Value("${LIANZHI_EMBEDDING_REVISION:1}") String revision){
        this.jdbc=jdbc;this.json=json;this.identity=identity;this.scope=scope;this.organizations=organizations;this.accounts=accounts;this.embeddings=embeddings;this.processor=processor;this.enabled=enabled;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);modelKey=hash((base+"\n"+model+"\n"+revision).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private Doc map(ResultSet r,int n)throws java.sql.SQLException{boolean stale="READY".equals(r.getString("indexing"))&&!modelKey.equals(r.getString("index_model"));return new Doc(UUID.fromString(r.getString("id")),r.getLong("version"),r.getString("name"),new DocumentAccess(r.getLong("company_id"),r.getLong("owner_user_id"),DocumentAccess.Visibility.valueOf(r.getString("visibility"))),r.getString("sha256"),r.getLong("byte_size"),r.getString("processing"),stale?"STALE":r.getString("indexing"),stale?"向量配置已变化，请重建索引；文字检索可用":r.getString("message"),r.getInt("chunk_count"),r.getLong("created_at"),r.getLong("updated_at"));}
    public boolean available(){if(!enabled)return false;try{return jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='6' AND success=TRUE",Long.class)==1;}catch(RuntimeException e){return false;}}
    public void requireAvailable(){if(!available())throw new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"知识库持久化未启用");}
    private Stamp stamp(Authentication actor){return new Stamp(identity.current(actor),scope.actor(actor).authVersion());}
    @Override public Object identity(Authentication actor){return stamp(actor);}
    private void check(Authentication actor,Stamp before){if(!before.equals(stamp(actor)))throw BusinessException.forbidden();}
    private void lock(){jdbc.queryForObject("SELECT id FROM knowledge_lock WHERE id=1 FOR UPDATE",Integer.class);}
    private <T>T write(Supplier<T> work){return tx.execute(s->{lock();return work.get();});}
    private Acl acl(KnowledgeActor actor){
        if(actor.role()==KnowledgeActor.Role.ADMIN)return new Acl("1=1",List.of());
        if(actor.companyId()==null)throw BusinessException.forbidden();
        if(actor.role()==KnowledgeActor.Role.HR)return new Acl("d.company_id=?",List.of(actor.companyId()));
        return new Acl("d.company_id=? AND (d.visibility='COMPANY' OR d.owner_user_id=?)",List.of(actor.companyId(),actor.userId()));
    }
    private Doc readable(Authentication actor,UUID id){requireAvailable();var a=acl(identity.current(actor));var args=new ArrayList<>(a.args());args.add(id.toString());var found=jdbc.query("SELECT "+COLUMNS+" FROM knowledge_document d WHERE d.processing<>'DELETED' AND "+a.sql()+" AND d.id=?",this::map,args.toArray());if(found.isEmpty())throw BusinessException.missing();return found.get(0);}
    private Doc managed(Authentication actor,UUID id){var d=readable(actor,id);if(!d.access().manageableBy(identity.current(actor)))throw BusinessException.forbidden();return d;}
    private static void version(Doc d,long expected){if(expected<1 || expected!=d.version())throw BusinessException.conflict("文档版本已变化，请刷新后检查");}
    public PageResult<Doc> list(Authentication actor,int page,int size){requireAvailable();PageResult.validate(page,size);var before=stamp(actor);var a=acl(before.actor());String where=" FROM knowledge_document d WHERE d.processing<>'DELETED' AND "+a.sql();long total=jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,a.args().toArray());var args=new ArrayList<>(a.args());args.add(size);args.add((long)(page-1)*size);var rows=jdbc.query("SELECT "+COLUMNS+where+" ORDER BY d.created_at DESC,d.id LIMIT ? OFFSET ?",this::map,args.toArray());check(actor,before);return new PageResult<>(List.copyOf(rows),total,page,size);}
    public Doc get(Authentication actor,UUID id){return readable(actor,id);}
    public List<Revision> revisions(Authentication actor,UUID id){var before=stamp(actor);readable(actor,id);var rows=jdbc.query("SELECT * FROM knowledge_revision WHERE document_id=? ORDER BY version DESC",(r,n)->new Revision(r.getLong("version"),r.getLong("actor_id"),r.getString("action"),r.getString("name"),r.getString("sha256"),r.getLong("occurred_at")),id.toString());check(actor,before);return List.copyOf(rows);}
    public Original original(Authentication actor,UUID id){var before=stamp(actor);var doc=readable(actor,id);var rows=jdbc.query("SELECT original_bytes FROM knowledge_document WHERE id=? AND version=? AND processing<>'DELETED'",(r,n)->r.getBytes(1),id.toString(),doc.version());if(rows.isEmpty()||readable(actor,id).version()!=doc.version())throw BusinessException.missing();check(actor,before);return new Original(doc.name(),rows.get(0));}
    private static String name(String name){if(name==null || name.isBlank() || name.length()>200 || name.codePoints().anyMatch(c->Character.isISOControl(c)||c=='/'||c=='\\'))throw BusinessException.invalid("文件名无效，不允许路径或控制字符");return name.strip();}
    private static byte[] input(byte[] bytes){if(bytes==null||bytes.length==0||bytes.length>10*1024*1024)throw BusinessException.invalid("文件须为1字节至10MiB");return bytes.clone();}
    public Doc upload(Authentication actor,long company,DocumentAccess.Visibility visibility,String name,byte[] bytes){
        requireAvailable();String filename=name(name);byte[] content=input(bytes);return write(()->{
            var before=stamp(actor);scope.company(actor,company);var c=organizations.company(company);if(c==null||!c.enabled())throw BusinessException.invalid("请选择有效公司");var access=new DocumentAccess(company,before.actor().userId(),visibility);if(!access.manageableBy(before.actor()))throw BusinessException.forbidden();
            if(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document",Long.class)>=1000 || jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE processing<>'DELETED'",Long.class)>=100)throw BusinessException.conflict("知识库文档容量已满");capacity(content.length,0);
            UUID id=UUID.randomUUID();long now=System.currentTimeMillis();jdbc.update("INSERT INTO knowledge_document(id,version,company_id,owner_user_id,visibility,name,sha256,byte_size,original_bytes,processing,indexing,message,created_at,updated_at) VALUES(?,1,?,?,?,?,?,?,?,'QUEUED','NOT_INDEXED','等待解析',?,?)",id.toString(),company,access.ownerUserId(),visibility.name(),filename,hash(content),content.length,content,now,now);audit(id,1,before.actor().userId(),"UPLOAD",filename,hash(content));check(actor,before);return readable(actor,id);
        });
    }
    public Doc replace(Authentication actor,UUID id,long expected,DocumentAccess.Visibility visibility,String name,byte[] bytes){requireAvailable();String filename=name(name);byte[] content=input(bytes);return write(()->{var before=stamp(actor);var old=managed(actor,id);version(old,expected);if(expected>=50)throw BusinessException.conflict("文档达到50版上限，请新建文档");var next=new DocumentAccess(old.access().companyId(),old.access().ownerUserId(),visibility);if(!next.manageableBy(before.actor()))throw BusinessException.forbidden();capacity(content.length,old.byteSize());clear(id);jdbc.update("UPDATE knowledge_document SET version=?,visibility=?,name=?,sha256=?,byte_size=?,original_bytes=?,processing='QUEUED',indexing='NOT_INDEXED',message='等待解析',lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=?",expected+1,visibility.name(),filename,hash(content),content.length,content,System.currentTimeMillis(),id.toString());audit(id,expected+1,before.actor().userId(),"REPLACE",filename,hash(content));check(actor,before);return readable(actor,id);});}
    public Doc retry(Authentication actor,UUID id,long expected){var d=managed(actor,id);var content=original(actor,id);return replace(actor,id,expected,d.access().visibility(),content.name(),content.bytes());}
    public void delete(Authentication actor,UUID id,long expected){requireAvailable();write(()->{var before=stamp(actor);var d=managed(actor,id);version(d,expected);clear(id);jdbc.update("UPDATE knowledge_document SET version=?,processing='DELETED',indexing='NOT_INDEXED',message='已删除',original_bytes=NULL,byte_size=0,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=?",expected+1,System.currentTimeMillis(),id.toString());audit(id,expected+1,before.actor().userId(),"DELETE",d.name(),d.sha256());check(actor,before);return null;});}
    private void capacity(long incoming,long previous){long used=jdbc.queryForObject("SELECT COALESCE(SUM(byte_size),0) FROM knowledge_document",Long.class);if(used-previous+incoming>100L*1024*1024)throw BusinessException.conflict("知识库原文件总量超过100MiB");}
    private void clear(UUID id){jdbc.update("DELETE FROM knowledge_chunk WHERE document_id=?",id.toString());jdbc.update("UPDATE knowledge_document SET chunk_count=0,text_size=0,vector_values=0,index_model=NULL WHERE id=?",id.toString());}
    private void audit(UUID id,long version,long actor,String action,String name,String hash){jdbc.update("INSERT INTO knowledge_revision(document_id,version,actor_id,action,name,sha256,occurred_at) VALUES(?,?,?,?,?,?,?)",id.toString(),version,actor,action,name,hash,System.currentTimeMillis());}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException("知识数据编码失败");}}
    private <T>T decode(String v,Class<T> type){try{return json.readValue(v,type);}catch(Exception e){throw new IllegalStateException("知识数据读取失败");}}
    public Citation cite(Authentication actor,UUID id,long version,int sequence){var before=stamp(actor);var d=readable(actor,id);if(d.version()!=version || !d.processing().equals("PARSED") || sequence<1)throw BusinessException.missing();var rows=jdbc.query("SELECT chunk_json FROM knowledge_chunk WHERE document_id=? AND version=? AND sequence_no=?",(r,n)->decode(r.getString(1),TextChunk.class),id.toString(),version,sequence);if(rows.isEmpty())throw BusinessException.missing();check(actor,before);return new Citation(id,version,rows.get(0));}
    @Override public List<Citation> search(Authentication actor,String question,int limit){return search(actor,question,Mode.TEXT,limit,0).stream().map(Hit::citation).toList();}
    public List<Hit> search(Authentication actor,String query,Mode mode,int limit,double minimum){
        requireAvailable();var before=stamp(actor);if(query==null||query.isBlank()||query.codePointCount(0,query.length())>500||limit<1||limit>50||mode==null||!Double.isFinite(minimum)||minimum<0||minimum>1)throw BusinessException.invalid("检索需1至500字，返回1至50项，阈值0至1");
        String text=TextNormalizer.normalize(query).toLowerCase(Locale.ROOT);Embedding needle=null;
        if(mode==Mode.VECTOR){if(embeddings.availability()!=ProviderAvailability.AVAILABLE)throw new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"向量服务未配置");var response=embeddings.embed(List.of(text));if(response.state()!=ProviderResult.State.SUCCESS||response.value().size()!=1)throw new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"向量服务暂不可用");needle=response.value().get(0);VectorMath.unit(needle);}
        check(actor,before);var a=acl(identity.current(actor));String where="d.processing='PARSED' AND "+a.sql();var args=new ArrayList<>(a.args());if(mode==Mode.VECTOR){var staleArgs=new ArrayList<>(a.args());staleArgs.add(modelKey);if(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document d WHERE "+where+" AND d.indexing='READY' AND (d.index_model IS NULL OR d.index_model<>?)",Long.class,staleArgs.toArray())>0)throw new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"向量配置已变化，请先重新解析与索引");where+=" AND d.indexing='READY' AND d.index_model=?";args.add(modelKey);}
        var records=jdbc.queryForList("SELECT d.id,d.version,d.name,c.chunk_json,c.vector_json FROM knowledge_document d JOIN knowledge_chunk c ON c.document_id=d.id AND c.version=d.version WHERE "+where+" ORDER BY d.id,c.sequence_no LIMIT 20001",args.toArray());if(records.size()>20_000)throw BusinessException.conflict("知识检索容量超限");
        var hits=new ArrayList<Hit>();for(var row:records){var chunk=decode((String)row.get("chunk_json"),TextChunk.class);double score;
            if(mode==Mode.TEXT)score=lexical(text,chunk.content().toLowerCase(Locale.ROOT));else{var vector=decode((String)row.get("vector_json"),Embedding.class);if(vector.values().size()!=needle.values().size())throw new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"向量模型维度变化，请重建索引");score=VectorMath.cosine(needle,vector);}
            if(score>0&&score>=minimum)hits.add(new Hit(new Citation(UUID.fromString((String)row.get("id")),((Number)row.get("version")).longValue(),chunk),(String)row.get("name"),mode,score));
        }
        check(actor,before);var selected=hits.stream().sorted(Comparator.comparingDouble(Hit::score).reversed().thenComparing(h->h.citation().documentId().toString()).thenComparingInt(h->h.citation().chunk().sequence())).limit(limit).toList();for(var hit:selected)cite(actor,hit.citation().documentId(),hit.citation().version(),hit.citation().chunk().sequence());return selected;
    }
    private static double lexical(String question,String content){if(content.contains(question))return 1;var terms=new HashSet<String>();for(String token:question.split("[^\\p{L}\\p{N}]+")){if(token.isBlank())continue;int[] cps=token.codePoints().toArray();boolean chinese=token.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN);if(chinese){for(int i=0;i<cps.length-1;i++)terms.add(new String(cps,i,2));}else if(token.length()>1)terms.add(token);}if(terms.isEmpty())return 0;return terms.stream().filter(content::contains).count()/(double)terms.size();}
    /** One bounded job; callers/scheduler can invoke after restart. Leases fail closed, never replay infinitely. */
    public boolean processNext(){
        if(!available())return false;
        Job job=write(()->{long now=System.currentTimeMillis();jdbc.update("UPDATE knowledge_document SET processing='FAILED',indexing='FAILED',message='处理被中断，请重试',lease_token=NULL,lease_until=NULL WHERE processing='PROCESSING' AND lease_until<?",now);var docs=jdbc.query("SELECT "+COLUMNS+" FROM knowledge_document d WHERE processing='QUEUED' ORDER BY created_at,id LIMIT 1",this::map);if(docs.isEmpty())return null;var d=docs.get(0);String token=UUID.randomUUID().toString();byte[] bytes=jdbc.queryForObject("SELECT original_bytes FROM knowledge_document WHERE id=?",byte[].class,d.id().toString());jdbc.update("UPDATE knowledge_document SET processing='PROCESSING',message='正在解析',lease_token=?,lease_until=?,updated_at=? WHERE id=?",token,now+300_000,now,d.id().toString());return new Job(d,bytes,token);});
        if(job==null)return false;
        List<TextChunk> chunks=List.of();List<Embedding> vectors=List.of();String state="FAILED",index="FAILED",message="文档处理失败";
        try{var sections=processor.parse(job.doc().name(),job.bytes());chunks=chunker.chunk(sections);if(chunks.isEmpty()){state="NO_TEXT";index="NOT_INDEXED";message="没有可提取正文";}else{state="PARSED";index="UNAVAILABLE";message="解析完成，文字检索可用；向量服务未配置或不可用";if(embeddings.availability()==ProviderAvailability.AVAILABLE){var all=new ArrayList<Embedding>();int dimension=0;boolean ok=true;for(int i=0;i<chunks.size();i+=32){var response=embeddings.embed(chunks.subList(i,Math.min(i+32,chunks.size())).stream().map(TextChunk::content).toList());if(response.state()!=ProviderResult.State.SUCCESS||response.value().size()!=Math.min(32,chunks.size()-i)){ok=false;break;}for(var v:response.value()){VectorMath.unit(v);if(dimension!=0&&dimension!=v.values().size())throw new IllegalArgumentException();dimension=v.values().size();all.add(v);}}if(ok){vectors=List.copyOf(all);index="READY";message="解析与向量索引完成";}}}}
        catch(DocumentContentProcessor.ProcessingException e){state=e.state();index="NOT_INDEXED";message=e.getMessage();}
        catch(RuntimeException e){state="FAILED";index="FAILED";message="解析资源超限、切片或向量结果无效";}
        final var output=chunks;final var vectorOutput=vectors;final String processed=state,indexed=index,note=message;
        write(()->{var d=jdbc.queryForList("SELECT version,processing,lease_token FROM knowledge_document WHERE id=?",job.doc().id().toString());if(d.isEmpty()||((Number)d.get(0).get("version")).longValue()!=job.doc().version()||!"PROCESSING".equals(d.get(0).get("processing"))||!job.token().equals(d.get(0).get("lease_token")))return null;
            long usedChunks=jdbc.queryForObject("SELECT COALESCE(SUM(chunk_count),0) FROM knowledge_document",Long.class),usedText=jdbc.queryForObject("SELECT COALESCE(SUM(text_size),0) FROM knowledge_document",Long.class),usedVectors=jdbc.queryForObject("SELECT COALESCE(SUM(vector_values),0) FROM knowledge_document",Long.class);
            long textSize=output.stream().mapToLong(c->c.content().codePointCount(0,c.content().length())).sum(),vectorSize=vectorOutput.stream().mapToLong(v->v.values().size()).sum();
            if(usedChunks+output.size()>20_000||usedText+textSize>4_000_000||usedVectors+vectorSize>1_000_000){jdbc.update("UPDATE knowledge_document SET processing='FAILED',indexing='FAILED',message='知识库索引容量超限',lease_token=NULL,lease_until=NULL WHERE id=?",job.doc().id().toString());return null;}
            if(processed.equals("PARSED"))for(int i=0;i<output.size();i++)jdbc.update("INSERT INTO knowledge_chunk(document_id,version,sequence_no,chunk_json,vector_json) VALUES(?,?,?,?,?)",job.doc().id().toString(),job.doc().version(),output.get(i).sequence(),encode(output.get(i)),vectorOutput.isEmpty()?null:encode(vectorOutput.get(i)));
            jdbc.update("UPDATE knowledge_document SET processing=?,indexing=?,message=?,chunk_count=?,text_size=?,vector_values=?,index_model=?,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=?",processed,indexed,note,processed.equals("PARSED")?output.size():0,processed.equals("PARSED")?textSize:0,processed.equals("PARSED")?vectorSize:0,indexed.equals("READY")?modelKey:null,System.currentTimeMillis(),job.doc().id().toString());return null;});return true;
    }
}
