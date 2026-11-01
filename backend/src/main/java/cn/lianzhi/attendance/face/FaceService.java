package cn.lianzhi.attendance.face;

import cn.lianzhi.attendance.attendance.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class FaceService implements FaceAttendancePort {
    private final JdbcTemplate jdbc;
    private final AttendanceStore attendance;
    private final ObjectProvider<FaceProvider> providers;
    private final ObjectProvider<AttendanceDirectory> directories;
    private final QrTokens tokens;
    private final FaceCipher cipher;
    private final boolean enabled, required;
    private final double minimumScore;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public FaceService(JdbcTemplate jdbc, AttendanceStore attendance, ObjectProvider<FaceProvider> providers,
                       ObjectProvider<AttendanceDirectory> directories, QrTokens tokens,
                       @Value("${lianzhi.face.enabled:false}") boolean enabled,
                       @Value("${lianzhi.face.required-for-punch:false}") boolean required,
                       @Value("${lianzhi.face.encryption-key:}") String encryptionKey,
                       @Value("${lianzhi.face.minimum-score:-1}") double minimumScore) {
        this(jdbc,attendance,providers,directories,tokens,enabled,required,encryptionKey,minimumScore,Clock.systemUTC());
    }
    FaceService(JdbcTemplate jdbc, AttendanceStore attendance, ObjectProvider<FaceProvider> providers,
                ObjectProvider<AttendanceDirectory> directories, QrTokens tokens, boolean enabled, boolean required,
                String encryptionKey, double minimumScore, Clock clock) {
        this.jdbc=jdbc; this.attendance=attendance; this.providers=providers; this.directories=directories;
        this.tokens=tokens; this.enabled=enabled; this.required=required; this.cipher=new FaceCipher(encryptionKey);
        this.minimumScore=minimumScore; this.clock=clock;
    }
    public record Status(boolean available, boolean detection, boolean comparison, boolean liveness, String provider,
                         String message, boolean storageAvailable, boolean enrolled, long revision, Instant updatedAt,
                         boolean punchRequired, boolean deletionAvailable) {}
    public record Enrollment(boolean enrolled, long revision, Instant updatedAt) {}
    public record ChallengeInput(String token, String requestId, String type) {}
    public record Challenge(String id, Instant expiresAt) {}
    public record Verification(boolean verified, String verificationId, Instant expiresAt, String message) {}
    private record Stored(long revision,String provider,String version,String encrypted,Instant updatedAt) {}
    private record Pending(String id,long workplace,String request,String type,String nonce,long revision,
                           Instant expires,int attempts,Instant verified,Instant consumed) {}

    public boolean requiredForPunch() { return required; }
    private boolean schemaReady() {
        try {
            Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('4','5') AND success=TRUE",Integer.class);
            if(count==null || count!=2) return false;
            jdbc.queryForObject("SELECT COUNT(*) FROM face_enrollment WHERE user_id=-1",Integer.class);
            jdbc.queryForObject("SELECT COUNT(*) FROM face_challenge WHERE user_id=-1",Integer.class);
            jdbc.queryForObject("SELECT COUNT(*) FROM face_audit WHERE user_id=-1",Integer.class);
            return true;
        } catch(org.springframework.dao.DataAccessException e) { return false; }
    }
    private FaceProvider provider() {
        List<FaceProvider> all=providers.orderedStream().toList();
        if(all.size()!=1) return null;
        try {
            FaceProvider p=all.get(0); var c=p.capabilities();
            if(!c.detection() || !c.comparison() || !c.liveness() || c.provider()==null || c.provider().isBlank()
                || c.provider().length()>100 || p.featureVersion()==null || p.featureVersion().isBlank() || p.featureVersion().length()>100) return null;
            return p;
        } catch(RuntimeException e) { return null; }
    }
    public Status status(long user) {
        boolean storage=schemaReady(); Stored stored=storage ? stored(user) : null;
        FaceProvider p=enabled ? provider() : null;
        boolean sdk=p!=null, policy=Double.isFinite(minimumScore) && minimumScore>0 && minimumScore<1;
        boolean available=enabled && sdk && storage && cipher.ready() && policy;
        String message=!enabled ? "人脸功能尚未启用；不会采集或上传人脸数据"
            : !sdk ? "虹软SDK与官方授权适配器尚未接入，检测、比对和活体不可用"
            : !storage ? "等待按顺序发布V4、V5，当前不能登记或验证"
            : !cipher.ready() ? "人脸加密密钥未正确配置，禁止保存特征"
            : !policy ? "尚未配置经实际SDK校准的比对阈值"
            : "服务端人脸流程可用，实际识别由已授权SDK执行";
        return new Status(available,sdk,sdk,sdk,sdk?p.capabilities().provider():"NONE",message,storage,
            stored!=null,stored==null?0:stored.revision(),stored==null?null:stored.updatedAt(),required,storage && stored!=null);
    }
    private FaceProvider ready() {
        if(!enabled || !schemaReady() || !cipher.ready() || !Double.isFinite(minimumScore) || minimumScore<=0 || minimumScore>=1)
            throw AttendanceProblem.unavailable("人脸服务未就绪，请检查SDK、授权、V4/V5、加密密钥及比对阈值");
        FaceProvider p=provider();
        if(p==null) throw AttendanceProblem.unavailable("虹软SDK或官方授权不可用，不能登记或验证");
        return p;
    }
    private Stored stored(long user) {
        return jdbc.query("SELECT * FROM face_enrollment WHERE user_id=?",(rs,n)->new Stored(rs.getLong("revision"),rs.getString("provider"),rs.getString("feature_version"),rs.getString("feature_cipher"),rs.getTimestamp("updated_at").toInstant()),user).stream().findFirst().orElse(null);
    }
    private String context(long user, Stored s) { return user+":"+s.revision()+":"+s.provider().length()+":"+s.provider()+":"+s.version().length()+":"+s.version(); }
    private void audit(long user,String action,long revision) {
        jdbc.update("INSERT INTO face_audit(user_id,action,enrollment_revision,occurred_at) VALUES(?,?,?,?)",user,action,revision,Timestamp.from(clock.instant()));
    }
    private static void image(byte[] data) {
        if(data==null || data.length==0 || data.length>2*1024*1024) throw new IllegalArgumentException("请使用不超过2MB的JPEG或PNG采集图像");
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext()) throw new IllegalArgumentException("图像无法读取，请重新采集JPEG或PNG");
            var reader=readers.next();
            try {
                reader.setInput(input);
                String format=reader.getFormatName(); int width=reader.getWidth(0),height=reader.getHeight(0);
                if(!Set.of("JPEG","JPG","PNG").contains(format.toUpperCase(Locale.ROOT)) || width<100 || height<100
                    || width>4096 || height>4096 || (long)width*height>8_000_000) throw new IllegalArgumentException("请使用100像素以上、尺寸不超过4096且不超过800万像素的JPEG或PNG");
            } finally { reader.dispose(); }
        } catch(java.io.IOException e) { throw new IllegalArgumentException("图像损坏，请重新采集"); }
    }
    private FaceProvider.Sample sample(FaceProvider p,byte[] data) {
        image(data);
        FaceProvider.Sample sample;
        try { synchronized(p) { sample=p.analyze(data); } }
        catch(RuntimeException e) { throw AttendanceProblem.unavailable("人脸SDK处理失败，请检查授权或稍后重试"); }
        if(sample==null) throw AttendanceProblem.unavailable("人脸SDK未返回有效结果");
        return sample;
    }
    private static boolean accepted(FaceProvider.Sample s) {
        return s.faces()==1 && s.qualityAccepted() && s.live() && s.feature()!=null && s.feature().length>0 && s.feature().length<=32768;
    }
    private static void wipe(byte[] value) { if(value!=null) Arrays.fill(value,(byte)0); }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Enrollment enroll(long user, byte[] image, long expectedRevision, boolean update, boolean consent) {
        FaceProvider p=ready();
        if(!consent) throw new IllegalArgumentException("请先明确同意本次人脸采集与特征处理");
        attendance.lockUser(user); Stored old=stored(user);
        if(update ? old==null || old.revision()!=expectedRevision : old!=null || expectedRevision!=0)
            throw new AttendanceProblem("FACE_REVISION_CONFLICT","登记状态已变化，请刷新后重新确认",org.springframework.http.HttpStatus.CONFLICT);
        FaceProvider.Sample s=sample(p,image);
        try {
            if(!accepted(s)) throw new IllegalArgumentException("请保持单人正脸、光线充足并通过实际活体检测后重试");
            long revision=jdbc.queryForObject("SELECT COALESCE(MAX(enrollment_revision),0)+1 FROM face_audit WHERE user_id=?",Long.class,user);
            Instant now=clock.instant();
            Stored next=new Stored(revision,p.capabilities().provider(),p.featureVersion(),null,now);
            String encrypted=cipher.encrypt(s.feature(),context(user,next));
            if(old==null) jdbc.update("INSERT INTO face_enrollment(user_id,revision,provider,feature_version,feature_cipher,updated_at) VALUES(?,?,?,?,?,?)",user,revision,next.provider(),next.version(),encrypted,Timestamp.from(now));
            else jdbc.update("UPDATE face_enrollment SET revision=?,provider=?,feature_version=?,feature_cipher=?,updated_at=? WHERE user_id=?",revision,next.provider(),next.version(),encrypted,Timestamp.from(now),user);
            jdbc.update("DELETE FROM face_challenge WHERE user_id=?",user);
            audit(user,update?"UPDATE":"ENROLL",revision);
            return new Enrollment(true,revision,now);
        } finally { wipe(s.feature()); }
    }
    /** Withdrawal stays available if the SDK or encryption key is lost. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Enrollment delete(long user,long expectedRevision) {
        if(!schemaReady()) throw AttendanceProblem.unavailable("人脸存储尚未发布，未执行删除");
        attendance.lockUser(user); Stored old=stored(user);
        if(old!=null && old.revision()!=expectedRevision) throw new AttendanceProblem("FACE_REVISION_CONFLICT","登记状态已变化，请刷新后重新确认删除",org.springframework.http.HttpStatus.CONFLICT);
        jdbc.update("DELETE FROM face_challenge WHERE user_id=?",user);
        if(old!=null) { jdbc.update("DELETE FROM face_enrollment WHERE user_id=?",user); audit(user,"DELETE",old.revision()); }
        return new Enrollment(false,0,null);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Challenge challenge(long user, ChallengeInput input) {
        ready();
        if(input==null || !Set.of("IN","OUT").contains(Objects.toString(input.type(),""))) throw new IllegalArgumentException("请选择打卡方向");
        uuid(input.requestId()); var qr=tokens.verify(input.token());
        AttendanceDirectory directory=directories.getIfAvailable();
        if(directory==null) throw AttendanceProblem.unavailable("组织与场所服务不可用");
        directory.forPunch(user,qr.workplaceId(),clock.instant());
        attendance.lockUser(user); Stored old=stored(user);
        if(old==null) throw new IllegalArgumentException("请先完成本人登记");
        // Expired rows contain no image or feature; discard them and any replaced request.
        jdbc.update("DELETE FROM face_challenge WHERE user_id=? AND (expires_at<=? OR request_id=?)",user,Timestamp.from(clock.instant()),input.requestId());
        String id=UUID.randomUUID().toString(); Instant expires=clock.instant().plusSeconds(30);
        jdbc.update("INSERT INTO face_challenge(id,user_id,workplace_id,request_id,event_type,qr_nonce,enrollment_revision,expires_at) VALUES(?,?,?,?,?,?,?,?)",id,user,qr.workplaceId(),input.requestId(),input.type(),qr.nonce(),old.revision(),Timestamp.from(expires));
        return new Challenge(id,expires);
    }
    private static void uuid(String value) {
        if(value==null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException("验证请求编号无效");
    }
    private Pending pending(long user,String id) {
        uuid(id);
        Pending p=jdbc.query("SELECT * FROM face_challenge WHERE id=? AND user_id=?",(rs,n)->new Pending(rs.getString("id"),rs.getLong("workplace_id"),rs.getString("request_id"),rs.getString("event_type"),rs.getString("qr_nonce"),rs.getLong("enrollment_revision"),rs.getTimestamp("expires_at").toInstant(),rs.getInt("attempts"),instant(rs.getTimestamp("verified_at")),instant(rs.getTimestamp("consumed_at"))),id,user).stream().findFirst().orElse(null);
        if(p==null || !clock.instant().isBefore(p.expires()) || p.consumed()!=null || (p.attempts()>=3 && p.verified()==null))
            throw new IllegalArgumentException("人脸挑战不存在、已过期或不可重用，请重新发起");
        return p;
    }
    private static Instant instant(Timestamp value) { return value==null?null:value.toInstant(); }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Verification verify(long user,String challengeId,byte[] image,boolean consent) {
        FaceProvider p=ready();
        if(!consent) throw new IllegalArgumentException("请先明确同意本次人脸采集与服务端识别");
        attendance.lockUser(user); Pending pending=pending(user,challengeId); Stored stored=stored(user);
        if(stored==null || stored.revision()!=pending.revision() || !stored.provider().equals(p.capabilities().provider()) || !stored.version().equals(p.featureVersion()))
            throw new IllegalArgumentException("人脸登记或SDK版本已变化，请重新登记并验证");
        if(pending.verified()!=null) throw new IllegalArgumentException("本次挑战已验证，请提交对应打卡或重新发起");
        FaceProvider.Sample sample=sample(p,image); byte[] enrolled=null;
        try {
            boolean verified=false;
            if(accepted(sample)) {
                try { enrolled=cipher.decrypt(stored.encrypted(),context(user,stored)); }
                catch(IllegalStateException e) { throw AttendanceProblem.unavailable("人脸特征不可读取，请检查加密配置或重新登记"); }
                double score;
                try { synchronized(p) { score=p.compare(enrolled,sample.feature()); } }
                catch(RuntimeException e) { throw AttendanceProblem.unavailable("人脸SDK比对失败，请稍后重试"); }
                verified=Double.isFinite(score) && score>=minimumScore && score<=1;
            }
            // A slow SDK response cannot turn an expired capture into a valid verification.
            if(!clock.instant().isBefore(pending.expires())) throw new IllegalArgumentException("人脸验证已超时，请重新扫码并发起挑战");
            jdbc.update("UPDATE face_challenge SET attempts=attempts+1,verified_at=? WHERE id=? AND user_id=?",verified?Timestamp.from(clock.instant()):null,challengeId,user);
            audit(user,verified?"VERIFY_OK":"VERIFY_FAIL",stored.revision());
            return new Verification(verified,verified?challengeId:null,pending.expires(),verified?"服务端验证通过，请提交本次打卡":"未通过单人检测、质量、活体或身份比对，请重新采集");
        } finally { wipe(enrolled); wipe(sample.feature()); }
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void consume(long user,long place,String nonce,String request,String type,String verificationId) {
        FaceProvider engine=ready(); attendance.lockUser(user); Pending p=pending(user,verificationId); Stored enrollment=stored(user);
        if(p.verified()==null || p.workplace()!=place || !p.nonce().equals(nonce) || !p.request().equals(request)
            || !p.type().equals(type) || enrollment==null || enrollment.revision()!=p.revision()
            || !enrollment.provider().equals(engine.capabilities().provider()) || !enrollment.version().equals(engine.featureVersion()))
            throw new IllegalArgumentException("人脸验证与当前员工、场所或打卡请求不匹配，请重新验证");
        if(jdbc.update("UPDATE face_challenge SET consumed_at=? WHERE id=? AND user_id=? AND consumed_at IS NULL",Timestamp.from(clock.instant()),verificationId,user)!=1)
            throw new IllegalArgumentException("人脸凭据已使用，请重新验证");
    }
}
