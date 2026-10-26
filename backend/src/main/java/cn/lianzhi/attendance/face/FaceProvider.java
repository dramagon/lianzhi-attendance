package cn.lianzhi.attendance.face;

/** Implement using an authorized SDK; capability reporting never implies successful verification. */
public interface FaceProvider {
    record Capabilities(boolean detection, boolean comparison, boolean liveness, String provider, String message) {}
    Capabilities capabilities();
}
