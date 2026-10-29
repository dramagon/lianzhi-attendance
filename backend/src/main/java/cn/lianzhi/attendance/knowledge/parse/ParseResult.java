package cn.lianzhi.attendance.knowledge.parse;

import cn.lianzhi.attendance.knowledge.text.TextSection;
import java.util.List;

public record ParseResult(DocumentFormat format, ParseCode code, String message, List<TextSection> sections) {
    public ParseResult {
        java.util.Objects.requireNonNull(format); java.util.Objects.requireNonNull(code);
        java.util.Objects.requireNonNull(message); sections = List.copyOf(sections);
        if (code != ParseCode.SUCCESS && !sections.isEmpty()) throw new IllegalArgumentException("失败结果不得包含部分正文");
    }
}
