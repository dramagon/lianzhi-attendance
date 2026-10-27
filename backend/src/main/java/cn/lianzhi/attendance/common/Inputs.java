package cn.lianzhi.attendance.common;

public final class Inputs {
    private Inputs() {}
    public static String text(String value, int max, String label) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw BusinessException.invalid(label + "不能为空且不能超过" + max + "字");
        return value.trim();
    }
    public static void required(Object value, String label) {
        if (value == null) throw BusinessException.invalid(label + "不能为空");
    }
}
