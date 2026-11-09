package cn.lianzhi.attendance.knowledge.text;

import java.util.Objects;

/** A location supplied by a real parser, never inferred from chunk length. */
public record SourceLocation(Kind kind, Integer ordinal, String name, Long startMillis, Long endMillis) {
    public SourceLocation {
        Objects.requireNonNull(kind, "来源类型不能为空");
        switch (kind) {
            case FILE -> {
                if (ordinal != null || name != null || startMillis != null || endMillis != null) invalid();
            }
            case PAGE, SLIDE -> {
                if (ordinal == null || ordinal < 1 || name != null || startMillis != null || endMillis != null) invalid();
            }
            case SHEET, SECTION -> {
                if (name == null || name.isBlank() || name.length() > 256
                        || ordinal != null || startMillis != null || endMillis != null) invalid();
                name = name.strip();
            }
            case TIME_RANGE -> {
                if (ordinal != null || name != null || startMillis == null || endMillis == null
                        || startMillis < 0 || endMillis <= startMillis) invalid();
            }
        }
    }

    public static SourceLocation file() { return new SourceLocation(Kind.FILE, null, null, null, null); }
    public static SourceLocation page(int page) { return new SourceLocation(Kind.PAGE, page, null, null, null); }
    public static SourceLocation slide(int slide) { return new SourceLocation(Kind.SLIDE, slide, null, null, null); }
    public static SourceLocation sheet(String name) { return new SourceLocation(Kind.SHEET, null, name, null, null); }
    public static SourceLocation section(String name) { return new SourceLocation(Kind.SECTION, null, name, null, null); }
    public static SourceLocation timeRange(long start, long end) {
        return new SourceLocation(Kind.TIME_RANGE, null, null, start, end);
    }

    private static void invalid() { throw new IllegalArgumentException("来源位置字段与类型不匹配或范围无效"); }

    public enum Kind { FILE, PAGE, SHEET, SLIDE, SECTION, TIME_RANGE }
}
