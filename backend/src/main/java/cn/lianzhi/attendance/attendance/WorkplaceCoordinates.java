package cn.lianzhi.attendance.attendance;

/** GCJ02 workplace normalization. Formula reference: googollee/eviltransform (BSD-2-Clause). */
public final class WorkplaceCoordinates {
    private WorkplaceCoordinates() {}

    public static boolean supported(String system) {
        return "WGS84".equals(system) || "GCJ02".equals(system);
    }

    public static GeoFence.Point toWgs84(double latitude, double longitude, String system) {
        var original = new GeoFence.Point(latitude, longitude, "WGS84");
        if ("WGS84".equals(system)) return original;
        if (!"GCJ02".equals(system)) throw new IllegalArgumentException("打卡场所支持WGS84或GCJ02坐标");
        if (longitude < 72.024 || longitude > 137.8147 || latitude < 0.8493 || latitude > 55.8071)
            throw new IllegalArgumentException("GCJ02坐标超出当前换算范围，请核对场所坐标系");
        double guessLat = latitude;
        double guessLon = longitude;
        for (int iteration = 0; iteration < 12; iteration++) {
            double[] projected = project(guessLat, guessLon);
            double errorLat = projected[0] - latitude;
            double errorLon = projected[1] - longitude;
            if (Math.abs(errorLat) < 1e-8 && Math.abs(errorLon) < 1e-8)
                return new GeoFence.Point(guessLat, guessLon, "WGS84");
            guessLat -= errorLat;
            guessLon -= errorLon;
        }
        throw new IllegalArgumentException("场所坐标换算未收敛，请重新核对坐标");
    }

    private static double[] project(double latitude, double longitude) {
        double x = longitude - 105;
        double y = latitude - 35;
        double common = (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
        double latOffset = -100 + 2*x + 3*y + 0.2*y*y + 0.1*x*y + 0.2*Math.sqrt(Math.abs(x));
        latOffset += common + (20*Math.sin(y*Math.PI) + 40*Math.sin(y*Math.PI/3)) * 2/3;
        latOffset += (160*Math.sin(y*Math.PI/12) + 320*Math.sin(y*Math.PI/30)) * 2/3;
        double lonOffset = 300 + x + 2*y + 0.1*x*x + 0.1*x*y + 0.1*Math.sqrt(Math.abs(x));
        lonOffset += common + (20*Math.sin(x*Math.PI) + 40*Math.sin(x*Math.PI/3)) * 2/3;
        lonOffset += (150*Math.sin(x*Math.PI/12) + 300*Math.sin(x*Math.PI/30)) * 2/3;
        double radians = Math.toRadians(latitude);
        double eccentricity = 0.00669342162296594323;
        double correction = 1 - eccentricity*Math.pow(Math.sin(radians), 2);
        double root = Math.sqrt(correction);
        double earthRadius = 6378137;
        latOffset = latOffset * 180 / (earthRadius*(1-eccentricity)/(correction*root)*Math.PI);
        lonOffset = lonOffset * 180 / (earthRadius/root*Math.cos(radians)*Math.PI);
        return new double[] {latitude + latOffset, longitude + lonOffset};
    }
}
