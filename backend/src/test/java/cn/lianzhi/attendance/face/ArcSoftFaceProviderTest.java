package cn.lianzhi.attendance.face;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class ArcSoftFaceProviderTest {
    @Test void missingLicenseFailsClosedWithoutExposingSecrets() {
        try(var provider=new ArcSoftFaceProvider("", "", "", "", .8)) {
            assertFalse(provider.capabilities().detection());
            assertFalse(provider.capabilities().comparison());
            assertFalse(provider.capabilities().liveness());
            assertThrows(IllegalStateException.class,()->provider.analyze(new byte[]{1}));
            assertThrows(IllegalStateException.class,()->provider.compare(new byte[]{1},new byte[]{1}));
        }
    }
    @Test void invalidNativeFilesDoNotBecomeAvailable() {
        try(var provider=new ArcSoftFaceProvider("missing.jar", "missing", "PRIVATE_APP", "PRIVATE_KEY", .8)) {
            assertFalse(provider.capabilities().detection());
            assertFalse(provider.capabilities().message().contains("PRIVATE"));
        }
    }
    @Test void qualityRejectsSmallClippedDarkAndFlatSamples() {
        byte[] flat=new byte[200*200*3];
        assertFalse(ArcSoftFaceProvider.quality(flat,200,200,0,0,200,200));
        java.util.Arrays.fill(flat,(byte)128);
        assertFalse(ArcSoftFaceProvider.quality(flat,200,200,0,0,200,200));
        assertFalse(ArcSoftFaceProvider.quality(flat,200,200,-1,0,200,200));
        assertFalse(ArcSoftFaceProvider.quality(flat,200,200,0,0,79,200));
    }
    @Test @EnabledIfEnvironmentVariable(named="ARCSOFT_REAL_TEST",matches="true")
    void actualLicensedWindowsEngineRejectsNonFaceAndCorruptImage() throws Exception {
        try(var provider=new ArcSoftFaceProvider(System.getenv("LIANZHI_ARCSOFT_JAR"),System.getenv("LIANZHI_ARCSOFT_NATIVE_DIRECTORY"),
                System.getenv("LIANZHI_ARCSOFT_APP_ID"),System.getenv("LIANZHI_ARCSOFT_SDK_KEY"),.8)) {
            assertTrue(provider.capabilities().detection(),provider.capabilities().message());
            assertTrue(provider.capabilities().comparison()); assertTrue(provider.capabilities().liveness());
            assertTrue(provider.featureVersion().startsWith("3.0."));
            assertThrows(IllegalArgumentException.class,()->provider.analyze(new byte[]{1,2,3}));
            var image=new BufferedImage(641,480,BufferedImage.TYPE_INT_RGB);
            var output=new ByteArrayOutputStream(); ImageIO.write(image,"png",output);
            FaceProvider.Sample sample=provider.analyze(output.toByteArray());
            assertEquals(0,sample.faces()); assertFalse(sample.live()); assertFalse(sample.qualityAccepted()); assertNull(sample.feature());
            assertThrows(IllegalArgumentException.class,()->provider.compare(new byte[0],new byte[]{1}));
            assertTrue(provider.capabilities().detection());
            provider.close(); assertFalse(provider.capabilities().detection());
        }
    }
}
