package cn.lianzhi.attendance.attendance;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Per-process signing key: restart invalidates outstanding short-lived challenges. */
@Component
public class QrTokens {
    private final byte[] key;
    private final Clock clock;
    public QrTokens() { this(randomKey(), Clock.systemUTC()); }
    QrTokens(byte[] key, Clock clock) { this.key = key.clone(); this.clock = clock; }
    public record Issued(String token, Instant expiresAt) {}
    public record Verified(long workplaceId, String nonce) {}
    public Issued issue(long workplaceId) {
        if (workplaceId <= 0) throw new IllegalArgumentException("场所编号无效");
        Instant expiry = clock.instant().plusSeconds(60);
        String payload = "1:" + workplaceId + ":" + expiry.getEpochSecond() + ":" + UUID.randomUUID();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return new Issued(encoded + "." + sign(encoded), expiry);
    }
    public Verified verify(String token) {
        try {
            if (token == null || token.length() > 512) throw new IllegalArgumentException();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]).getBytes(StandardCharsets.US_ASCII), parts[1].getBytes(StandardCharsets.US_ASCII)))
                throw new IllegalArgumentException();
            String[] fields = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8).split(":");
            if (fields.length != 4 || !fields[0].equals("1")) throw new IllegalArgumentException();
            long place = Long.parseLong(fields[1]), expiry = Long.parseLong(fields[2]), now = clock.instant().getEpochSecond();
            if (place <= 0 || expiry <= now || expiry > now + 60) throw new IllegalArgumentException();
            UUID.fromString(fields[3]);
            return new Verified(place, fields[3]);
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException("二维码无效或已过期，请重新扫码"); }
    }
    private String sign(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException("二维码签名服务不可用", e); }
    }
    private static byte[] randomKey() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return bytes; }
}
