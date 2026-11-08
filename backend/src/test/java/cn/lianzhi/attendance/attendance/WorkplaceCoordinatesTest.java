package cn.lianzhi.attendance.attendance;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WorkplaceCoordinatesTest {
    @Test void knownBeijingPairNormalizesWithinTenCentimeters() {
        var converted = WorkplaceCoordinates.toWgs84(39.91022649807321, 116.4037135824225, "GCJ02");
        assertThat(GeoFence.distance(converted, new GeoFence.Point(39.908823, 116.397470, "WGS84"))).isLessThan(0.1);
        assertThat(converted.coordinateSystem()).isEqualTo("WGS84");
    }
    @Test void gpsCoordinatesKeepTheirOriginalValues() {
        assertThat(WorkplaceCoordinates.toWgs84(31.2, 121.5, "WGS84")).isEqualTo(new GeoFence.Point(31.2, 121.5, "WGS84"));
    }
    @Test void unsupportedOrInvalidCoordinatesFailClearly() {
        assertThatThrownBy(() -> WorkplaceCoordinates.toWgs84(0, 0, "GCJ02")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkplaceCoordinates.toWgs84(31, 121, "BD09")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkplaceCoordinates.toWgs84(Double.NaN, 121, "GCJ02")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void radiusAndAccuracyRemainEnforcedAfterConversion() {
        var workplace = WorkplaceCoordinates.toWgs84(39.91022649807321, 116.4037135824225, "GCJ02");
        assertThat(GeoFence.check(new GeoFence.Point(39.908823,116.397470,"WGS84"),workplace,5,10)).isLessThan(0.1);
        assertThatThrownBy(() -> GeoFence.check(new GeoFence.Point(39.92,116.41,"WGS84"),workplace,5,10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GeoFence.check(workplace,workplace,20,10)).isInstanceOf(IllegalArgumentException.class);
    }
}
