package cn.lianzhi.attendance.assistant;

import java.time.Instant;

/** Optional cross-process guard. Database requestKey remains the business idempotency authority. */
public interface SharedConfirmationGuard {
    record Claim(String token, boolean replay) {}
    void prepared(String sessionId, String confirmationId, Instant expiresAt);
    Claim claim(String sessionId, String confirmationId);
    void submitted(String sessionId, String confirmationId, Claim claim);
    void failed(String sessionId, String confirmationId, Claim claim);
    void cancel(String sessionId, String confirmationId);
}
