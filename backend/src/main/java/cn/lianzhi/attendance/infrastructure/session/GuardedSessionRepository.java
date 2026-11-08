package cn.lianzhi.attendance.infrastructure.session;

import java.util.function.Supplier;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

/** Redis failures must never select a process-local authenticated session. */
final class GuardedSessionRepository<S extends Session> implements SessionRepository<S> {
    private final SessionRepository<S> delegate;
    GuardedSessionRepository(SessionRepository<S> delegate) { this.delegate = delegate; }
    private <T> T call(Supplier<T> operation) {
        try { return operation.get(); }
        catch (RuntimeException error) { throw new SessionStoreUnavailable(error); }
    }
    @Override public S createSession() { return call(delegate::createSession); }
    @Override public S findById(String id) { return call(() -> delegate.findById(id)); }
    @Override public void save(S session) { call(() -> { delegate.save(session); return null; }); }
    @Override public void deleteById(String id) { call(() -> { delegate.deleteById(id); return null; }); }
}
