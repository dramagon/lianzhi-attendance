package cn.lianzhi.attendance.workplace;

import java.math.BigDecimal;

public record WorkplaceView(Long id, Long companyId, String name, BigDecimal latitude,
                            BigDecimal longitude, String coordinateSystem, Integer radiusMeters, Boolean enabled) {}
