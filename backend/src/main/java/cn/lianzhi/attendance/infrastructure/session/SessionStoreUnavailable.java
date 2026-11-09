package cn.lianzhi.attendance.infrastructure.session;

/** Carries only a fixed public message; provider diagnostics stay outside API responses. */
public final class SessionStoreUnavailable extends RuntimeException {
    public SessionStoreUnavailable(RuntimeException cause) { super("Session store unavailable", cause); }
}
