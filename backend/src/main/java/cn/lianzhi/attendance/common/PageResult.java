package cn.lianzhi.attendance.common;

import java.util.List;

public record PageResult<T>(List<T> items, long total, int page, int size) {
    public static void validate(int page, int size) {
        if (page < 1 || page > 1000000 || size < 1 || size > 100) throw BusinessException.invalid("page须为1～1000000，size须为1～100");
    }
}
