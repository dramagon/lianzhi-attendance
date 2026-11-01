package cn.lianzhi.attendance.face;

/** Trusted server call inside the same attendance write transaction. */
public interface FaceAttendancePort {
    boolean requiredForPunch();
    void consume(long userId, long workplaceId, String qrNonce, String requestId, String type, String verificationId);
}
