package cn.lianzhi.attendance.infrastructure.session;

import cn.lianzhi.attendance.assistant.SharedConfirmationGuard;
import cn.lianzhi.attendance.common.BusinessException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Atomic state transitions prevent a stale session copy from undoing cancellation or submission. */
final class RedisConfirmationGuard implements SharedConfirmationGuard {
    private static final DefaultRedisScript<Long> PREPARE = script("""
            local state=redis.call('GET',KEYS[1])
            if not state then redis.call('SET',KEYS[1],'OPEN','PX',ARGV[1]); return 1 end
            if state=='OPEN' then return 1 end
            return 0
            """);
    private static final DefaultRedisScript<Long> CLAIM = script("""
            local state=redis.call('GET',KEYS[1])
            if state=='OPEN' then redis.call('SET',KEYS[1],ARGV[1],'KEEPTTL'); return 1 end
            if state=='DONE' then return 2 end
            if state and string.sub(state,1,6)=='CLAIM:' then return 3 end
            return 0
            """);
    private static final DefaultRedisScript<Long> FINISH = script("""
            local state=redis.call('GET',KEYS[1])
            if state==ARGV[1] then redis.call('SET',KEYS[1],'DONE','KEEPTTL'); return 1 end
            if state=='DONE' then return 1 end
            return 0
            """);
    private static final DefaultRedisScript<Long> RELEASE = script("""
            if redis.call('GET',KEYS[1])==ARGV[1] then redis.call('SET',KEYS[1],'OPEN','KEEPTTL'); return 1 end
            return 0
            """);
    private static final DefaultRedisScript<Long> CANCEL = script("""
            local state=redis.call('GET',KEYS[1])
            if state=='OPEN' then redis.call('SET',KEYS[1],'CANCELLED','KEEPTTL'); return 1 end
            if state=='CANCELLED' then return 1 end
            return 0
            """);
    private final StringRedisTemplate redis;
    private final String prefix;
    RedisConfirmationGuard(StringRedisTemplate redis, String namespace) {
        this.redis = redis; this.prefix = namespace + ":confirmations:";
    }
    private static DefaultRedisScript<Long> script(String value) { return new DefaultRedisScript<>(value, Long.class); }
    private String key(String session, String id) {
        if (session == null || session.isEmpty() || session.length() > 200 || id == null || !id.matches("[0-9a-f-]{36}"))
            throw BusinessException.invalid("确认编号无效");
        return prefix + session + ":" + id;
    }
    private long run(DefaultRedisScript<Long> script, String session, String id, String... values) {
        try {
            Long value = redis.execute(script, List.of(key(session, id)), (Object[]) values);
            if (value == null) throw new IllegalStateException("Missing Redis script result");
            return value;
        } catch (org.springframework.dao.DataAccessException error) { throw new SessionStoreUnavailable(error); }
    }
    @Override public void prepared(String session, String id, Instant expiry) {
        long remaining = Duration.between(Instant.now(), expiry).toMillis();
        if (remaining <= 0 || remaining > Duration.ofMinutes(11).toMillis()) throw BusinessException.conflict("确认已过期，请重新准备");
        if (run(PREPARE, session, id, Long.toString(remaining)) != 1) throw BusinessException.conflict("此草稿已处理，请核对已有申请");
    }
    @Override public Claim claim(String session, String id) {
        String token = "CLAIM:" + UUID.randomUUID();
        long result = run(CLAIM, session, id, token);
        if (result == 1) return new Claim(token, false);
        if (result == 2) return new Claim("DONE", true);
        if (result == 3) throw BusinessException.conflict("此确认正在处理，请先查询已有申请后重试同一确认");
        throw BusinessException.conflict("确认已取消或失效，请重新核对");
    }
    @Override public void submitted(String session, String id, Claim claim) {
        if (run(FINISH, session, id, claim.token()) != 1) throw BusinessException.conflict("确认状态已变化，请查询已有申请");
    }
    @Override public void failed(String session, String id, Claim claim) {
        if (!claim.replay()) run(RELEASE, session, id, claim.token());
    }
    @Override public void cancel(String session, String id) {
        if (run(CANCEL, session, id) != 1) throw BusinessException.conflict("确认已提交、正在处理或已失效，请核对申请列表");
    }
}
