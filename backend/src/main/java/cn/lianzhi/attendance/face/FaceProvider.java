package cn.lianzhi.attendance.face;

/** Implement using an authorized SDK; capability reporting never implies successful verification. */
public interface FaceProvider {
    record Capabilities(boolean detection, boolean comparison, boolean liveness, String provider, String message) {}
    Capabilities capabilities();
    /** Official SDK feature format/version, not the application's version. */
    String featureVersion();
    /** Adapter must run actual detection, quality checks and SDK liveness, never client flags. */
    record Sample(int faces, boolean qualityAccepted, boolean live, byte[] feature) {}
    Sample analyze(byte[] encodedImage);
    /** Normalize the actual SDK score to [0,1]; never decide identity on the client. */
    double compare(byte[] enrolledFeature, byte[] candidateFeature);
}
