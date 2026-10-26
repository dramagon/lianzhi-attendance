package cn.lianzhi.attendance.attendance;

public final class GeoFence {
    private GeoFence() {}
    public record Point(double latitude, double longitude, String coordinateSystem) {
        public Point {
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)
                throw new IllegalArgumentException("经纬度超出有效范围");
            if (!"WGS84".equals(coordinateSystem)) throw new IllegalArgumentException("当前仅支持 WGS84 坐标；请先由场所提供方完成坐标转换");
        }
    }
    public static double distance(Point a, Point b) {
        double dLat = Math.toRadians(b.latitude - a.latitude), dLon = Math.toRadians(b.longitude - a.longitude);
        double h = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(a.latitude)) * Math.cos(Math.toRadians(b.latitude)) * Math.pow(Math.sin(dLon / 2), 2);
        return 6371008.8 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
    }
    public static double check(Point location, Point workplace, double accuracy, int radius) {
        if (!Double.isFinite(accuracy) || accuracy <= 0 || radius < 1 || radius > 5000) throw new IllegalArgumentException("定位精度或场所半径无效");
        if (accuracy > Math.min(radius, 100)) throw new IllegalArgumentException("定位精度不足，请到开阔处重新定位");
        double distance = distance(location, workplace);
        if (distance > radius) throw new IllegalArgumentException("当前位置超出允许打卡范围");
        return distance;
    }
}
