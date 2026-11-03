package cn.lianzhi.attendance.face;

import cn.lianzhi.attendance.attendance.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Business/security tests with an explicit test-only SDK simulator. No actual recognition claim. */
@SpringBootTest(properties={
 "spring.datasource.url=${LIANZHI_FACE_TEST_URL:jdbc:h2:mem:b-face;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
 "lianzhi.face.enabled=true", "lianzhi.face.required-for-punch=true", "lianzhi.face.minimum-score=0.8",
 // Public, disposable unit-test key, never an operational credential.
 "lianzhi.face.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@ActiveProfiles("test") @Import(FaceWorkflowTest.SimulatorConfig.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@WithMockUser(username="employee",roles="EMPLOYEE")
class FaceWorkflowTest {
    @TestConfiguration static class SimulatorConfig { @Bean Simulator simulator(){return new Simulator();} }
    static class Simulator implements FaceProvider {
        int faces=1; boolean live=true,quality=true; double score=0.95;
        public Capabilities capabilities(){return new Capabilities(true,true,true,"TEST_ONLY_SIMULATOR","not a real SDK");}
        public String featureVersion(){return "test-format-1";}
        public Sample analyze(byte[] image){return new Sample(faces,quality,live,new byte[]{11,22,33,44});}
        public double compare(byte[] enrolled,byte[] candidate){return score;}
    }
    @Autowired FaceService faces; @Autowired Simulator sdk; @Autowired JdbcTemplate jdbc;
    @Autowired AttendanceStore store; @Autowired QrTokens tokens; @Autowired AttendanceSchedules schedules;
    @Autowired AttendanceService attendance; @Autowired ObjectProvider<FaceProvider> providers;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired ObjectProvider<AttendanceDirectory> directories; @Autowired PlatformTransactionManager transactions;
    long user,place,company; byte[] image; LocalDate today=LocalDate.now(AttendanceRules.ZONE);
    @BeforeEach void prepare() throws Exception {
        if(System.getenv("LIANZHI_FACE_TEST_URL")!=null) {
            try(var connection=jdbc.getDataSource().getConnection()) {
                assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            }
        }
        jdbc.update("DELETE FROM face_challenge");jdbc.update("DELETE FROM face_enrollment");jdbc.update("DELETE FROM face_audit");
        jdbc.update("DELETE FROM attendance_event");jdbc.update("DELETE FROM attendance_schedule");
        jdbc.update("DELETE FROM employee_workplace");jdbc.update("DELETE FROM workplace");
        user=jdbc.queryForObject("SELECT id FROM sys_user WHERE username='employee'",Long.class);
        company=jdbc.queryForObject("SELECT company_id FROM sys_user WHERE id=?",Long.class,user);
        jdbc.update("INSERT INTO workplace(company_id,name,latitude,longitude,coordinate_system,radius_meters) VALUES(?,'人脸业务测试场所',31.2,121.5,'WGS84',200)",company);
        place=jdbc.queryForObject("SELECT id FROM workplace WHERE name='人脸业务测试场所'",Long.class);
        jdbc.update("INSERT INTO employee_workplace(user_id,workplace_id) VALUES(?,?)",user,place);
        var rule=new AttendanceRules.Rule(LocalTime.MIDNIGHT,LocalTime.of(23,59),null,null,0,EnumSet.allOf(DayOfWeek.class));
        jdbc.update("INSERT INTO attendance_schedule(user_id,business_date,workplace_id,rule_json,holiday,created_by) VALUES(?,?,?,?,FALSE,?)",user,today,place,schedules.encode(rule),user);
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(200,200,BufferedImage.TYPE_INT_RGB),"jpg",bytes);image=bytes.toByteArray();
        sdk.faces=1;sdk.live=true;sdk.quality=true;sdk.score=0.95;
    }
    FaceService.Challenge challenge(String request,String token){return faces.challenge(user,new FaceService.ChallengeInput(token,request,"IN"));}
    FaceService.Verification verify(String request,String token){var c=challenge(request,token);return faces.verify(user,c.id(),image,true);}
    AttendanceService.Punch punch(String request,String token,String verification){return new AttendanceService.Punch("IN",token,request,new GeoFence.Point(31.2,121.5,"WGS84"),10.0,verification);}
    @Test void enrollmentIsEncryptedRevisionProtectedAndDeletionRevokesChallenges(){
        assertThat(faces.status(user).available()).isTrue();
        var first=faces.enroll(user,image,0,false,true);
        assertThat(first.revision()).isEqualTo(1);
        String encrypted=jdbc.queryForObject("SELECT feature_cipher FROM face_enrollment WHERE user_id=?",String.class,user);
        assertThat(encrypted).isNotEqualTo(Base64.getEncoder().encodeToString(new byte[]{11,22,33,44}));
        String id=verify(UUID.randomUUID().toString(),tokens.issue(place).token()).verificationId();
        assertThatThrownBy(()->faces.enroll(user,image,0,false,true)).isInstanceOf(AttendanceProblem.class);
        var second=faces.enroll(user,image,first.revision(),true,true);assertThat(second.revision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM face_challenge WHERE id=?",Integer.class,id)).isZero();
        assertThatThrownBy(()->faces.delete(user,first.revision())).isInstanceOf(AttendanceProblem.class);
        faces.delete(user,second.revision());assertThat(faces.status(user).enrolled()).isFalse();
        faces.delete(user,second.revision());
        assertThat(faces.enroll(user,image,0,false,true).revision()).isEqualTo(3);
    }
    @Test void unconfiguredDefaultNeverInvokesSdkOrStoresFeatures(){
        var closed=new FaceService(jdbc,store,providers,directories,tokens,false,true,"",-1,Clock.systemUTC());
        assertThat(closed.status(user).available()).isFalse();
        assertThatThrownBy(()->closed.enroll(user,image,0,false,true)).isInstanceOf(AttendanceProblem.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM face_enrollment",Integer.class)).isZero();
    }
    @Test void consentAndRealAnalysisFlagsAreRequiredForEnrollment(){
        assertThatThrownBy(()->faces.enroll(user,image,0,false,false)).isInstanceOf(IllegalArgumentException.class);
        sdk.live=false;assertThatThrownBy(()->faces.enroll(user,image,0,false,true)).isInstanceOf(IllegalArgumentException.class);
        sdk.live=true;sdk.faces=2;assertThatThrownBy(()->faces.enroll(user,image,0,false,true)).isInstanceOf(IllegalArgumentException.class);
        sdk.faces=1;sdk.quality=false;assertThatThrownBy(()->faces.enroll(user,image,0,false,true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM face_enrollment",Integer.class)).isZero();
    }
    @Test void malformedAndOversizedImagesCannotReachFeatureStorage(){
        for(byte[] bad:List.of(new byte[]{1,2,3},new byte[2*1024*1024+1]))
            assertThatThrownBy(()->faces.enroll(user,bad,0,false,true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void failedLivenessOrComparisonNeverCreatesCredentialAndThreeAttemptsExhaustChallenge(){
        faces.enroll(user,image,0,false,true);var c=challenge(UUID.randomUUID().toString(),tokens.issue(place).token());
        assertThatThrownBy(()->faces.verify(user,c.id(),image,false)).isInstanceOf(IllegalArgumentException.class);
        sdk.live=false;assertThat(faces.verify(user,c.id(),image,true).verified()).isFalse();
        sdk.live=true;sdk.score=Double.NaN;assertThat(faces.verify(user,c.id(),image,true).verificationId()).isNull();
        sdk.score=0.1;assertThat(faces.verify(user,c.id(),image,true).verified()).isFalse();
        assertThatThrownBy(()->faces.verify(user,c.id(),image,true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void thirdSuccessfulAttemptIsConsumableAndCannotBeReplayed(){
        faces.enroll(user,image,0,false,true);String request=UUID.randomUUID().toString(),token=tokens.issue(place).token();var c=challenge(request,token);
        sdk.score=0.1;faces.verify(user,c.id(),image,true);faces.verify(user,c.id(),image,true);
        sdk.score=0.95;assertThat(faces.verify(user,c.id(),image,true).verified()).isTrue();
        new TransactionTemplate(transactions).executeWithoutResult(s->faces.consume(user,place,tokens.verify(token).nonce(),request,"IN",c.id()));
        assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(s->faces.consume(user,place,tokens.verify(token).nonce(),request,"IN",c.id()))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void proofBindsUserPlaceNonceRequestAndDirectionAndConsumesWithAttendance(){
        faces.enroll(user,image,0,false,true);String request=UUID.randomUUID().toString(),token=tokens.issue(place).token();String id=verify(request,token).verificationId();
        var tx=new TransactionTemplate(transactions);String nonce=tokens.verify(token).nonce();
        long other=jdbc.queryForObject("SELECT id FROM sys_user WHERE username='admin'",Long.class);
        assertThatThrownBy(()->tx.executeWithoutResult(s->faces.consume(other,place,nonce,request,"IN",id))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->tx.executeWithoutResult(s->faces.consume(user,place+1,nonce,request,"IN",id))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->tx.executeWithoutResult(s->faces.consume(user,place,"other",request,"IN",id))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->tx.executeWithoutResult(s->faces.consume(user,place,nonce,UUID.randomUUID().toString(),"IN",id))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->tx.executeWithoutResult(s->faces.consume(user,place,nonce,request,"OUT",id))).isInstanceOf(IllegalArgumentException.class);
        var event=attendance.punch(user,punch(request,token,id));
        assertThat(attendance.punch(user,punch(request,token,id)).id()).isEqualTo(event.id());
        assertThat(store.day(user,today)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM face_challenge WHERE id=?",Boolean.class,id)).isTrue();
    }
    @Test void consumeRollsBackWithOuterTransaction(){
        faces.enroll(user,image,0,false,true);String request=UUID.randomUUID().toString(),token=tokens.issue(place).token();String id=verify(request,token).verificationId();
        assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(s->{
            attendance.punch(user,punch(request,token,id));throw new IllegalStateException("outer rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(store.day(user,today)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM face_challenge WHERE id=?",Boolean.class,id)).isTrue();
        attendance.punch(user,punch(request,token,id));
    }
    @Test void expiredChallengeAndChangedEnrollmentCannotVerify(){
        var enrollment=faces.enroll(user,image,0,false,true);var c=challenge(UUID.randomUUID().toString(),tokens.issue(place).token());
        jdbc.update("UPDATE face_challenge SET expires_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),c.id());
        assertThatThrownBy(()->faces.verify(user,c.id(),image,true)).isInstanceOf(IllegalArgumentException.class);
        String id=challenge(UUID.randomUUID().toString(),tokens.issue(place).token()).id();
        faces.enroll(user,image,enrollment.revision(),true,true);
        assertThatThrownBy(()->faces.verify(user,id,image,true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void deletionWorksWithoutSdkOrEncryptionKey(){
        var e=faces.enroll(user,image,0,false,true);
        var unavailable=new FaceService(jdbc,store,providers,directories,tokens,false,true,"",-1,Clock.systemUTC());
        new TransactionTemplate(transactions).executeWithoutResult(s->unavailable.delete(user,e.revision()));
        assertThat(faces.status(user).enrolled()).isFalse();
    }
    @Test void tamperedOrWrongIdentityCiphertextFailsClosed(){
        var cipher=new FaceCipher(Base64.getEncoder().encodeToString(new byte[32]));
        String encrypted=cipher.encrypt(new byte[]{11,22,33},"user:1:revision:1");
        assertThatThrownBy(()->cipher.decrypt(encrypted,"user:2:revision:1")).isInstanceOf(IllegalStateException.class);
        byte[] packed=Base64.getDecoder().decode(encrypted);packed[packed.length-1]^=1;
        assertThatThrownBy(()->cipher.decrypt(Base64.getEncoder().encodeToString(packed),"user:1:revision:1")).isInstanceOf(IllegalStateException.class);
    }
    @Test void multipartEndpointsEnforceConsentCsrfAndCurrentOwner() throws Exception {
        var csrf=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/face/enrollment").file(new org.springframework.mock.web.MockMultipartFile("image","capture.jpg","image/jpeg",image.clone())).param("consent","true"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/face/enrollment").file(new org.springframework.mock.web.MockMultipartFile("image","capture.jpg","image/jpeg",image.clone())).with(csrf))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/face/enrollment").file(new org.springframework.mock.web.MockMultipartFile("image","capture.jpg","image/jpeg",image.clone())).param("consent","true").param("userId","1").with(csrf))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.revision").value(1))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.feature").doesNotExist());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/face/enrollment").param("expectedRevision","1").with(csrf)
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThat(faces.status(user).enrolled()).isTrue();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/face/enrollment").file(new org.springframework.mock.web.MockMultipartFile("image","capture.jpg","image/jpeg",image.clone())).param("expectedRevision","1").param("consent","true").with(csrf).with(request->{request.setMethod("PUT");return request;}))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.revision").value(2));
    }
    @Test void encryptedFeatureCannotBeReadUsingWrongConfiguredKey(){
        faces.enroll(user,image,0,false,true);String request=UUID.randomUUID().toString(),token=tokens.issue(place).token();var c=challenge(request,token);
        byte[] other=new byte[32];Arrays.fill(other,(byte)7);
        var wrong=new FaceService(jdbc,store,providers,directories,tokens,true,true,Base64.getEncoder().encodeToString(other),0.8,Clock.systemUTC());
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(s->wrong.verify(user,c.id(),image,true))).isInstanceOf(AttendanceProblem.class);
    }
    @Test void concurrentConsumptionSucceedsOnlyOnce() throws Exception {
        faces.enroll(user,image,0,false,true);String request=UUID.randomUUID().toString(),token=tokens.issue(place).token();String id=verify(request,token).verificationId();
        String nonce=tokens.verify(token).nonce();var start=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> task=()->{
                start.await();
                try { new TransactionTemplate(transactions).executeWithoutResult(s->faces.consume(user,place,nonce,request,"IN",id));return true; }
                catch(IllegalArgumentException e) { return false; }
            };
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();
            assertThat(List.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        } finally { pool.shutdownNow(); }
    }
}
