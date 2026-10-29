package cn.lianzhi.attendance.attendance;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class QrAndLocationTest {
    private final Instant now=Instant.parse("2026-10-26T01:00:00Z");
    private final byte[] key=new byte[32];
    @Test void signedTokenBindsWorkplaceAndNonce() { var tokens=new QrTokens(key,Clock.fixed(now,ZoneOffset.UTC)); var a=tokens.issue(7); assertThat(tokens.verify(a.token()).workplaceId()).isEqualTo(7); assertThat(tokens.verify(a.token()).nonce()).isNotEqualTo(tokens.verify(tokens.issue(7).token()).nonce()); }
    @Test void tamperingAndWrongKeyFail() { var t=new QrTokens(key,Clock.fixed(now,ZoneOffset.UTC)); String token=t.issue(7).token(); assertThatThrownBy(()->t.verify("A"+token.substring(1))).isInstanceOf(IllegalArgumentException.class); byte[] other=key.clone();other[0]=1;assertThatThrownBy(()->new QrTokens(other,Clock.fixed(now,ZoneOffset.UTC)).verify(token)).isInstanceOf(IllegalArgumentException.class); }
    @Test void expiredTokensFailAtExactBoundary() { var a=new QrTokens(key,Clock.fixed(now,ZoneOffset.UTC)).issue(7); var after=new QrTokens(key,Clock.fixed(now.plusSeconds(60),ZoneOffset.UTC)); assertThatThrownBy(()->after.verify(a.token())).isInstanceOf(IllegalArgumentException.class); }
    @Test void malformedAndHugeTokensFail() {var t=new QrTokens(key,Clock.systemUTC()); for(String bad:new String[]{"", "abc", "x.y", "A".repeat(1000)})assertThatThrownBy(()->t.verify(bad)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->t.verify(null)).isInstanceOf(IllegalArgumentException.class); }
    @Test void haversineDistanceAndRangeAreChecked() {var p=new GeoFence.Point(31.23,121.47,"WGS84");assertThat(GeoFence.distance(p,p)).isZero();assertThat(GeoFence.distance(new GeoFence.Point(0,0,"WGS84"),new GeoFence.Point(0,1,"WGS84"))).isBetween(111000.0,112000.0);assertThat(GeoFence.check(p,p,10,200)).isZero();assertThatThrownBy(()->GeoFence.check(new GeoFence.Point(32,121,"WGS84"),p,10,200)).isInstanceOf(IllegalArgumentException.class);}
    @Test void badAccuracyAndCoordinateSystemsAreRejected() {var p=new GeoFence.Point(0,0,"WGS84");assertThatThrownBy(()->GeoFence.check(p,p,300,200)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->GeoFence.check(p,p,0,200)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->new GeoFence.Point(Double.NaN,0,"WGS84")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->new GeoFence.Point(0,0,"GCJ02")).isInstanceOf(IllegalArgumentException.class);}
}
