package cn.lianzhi.attendance.knowledge.text;

import java.util.Objects;

/** One parser section. Keep separate pages/sheets/slides/time ranges separate. */
public record TextSection(SourceLocation source, String text) {
    public TextSection {
        Objects.requireNonNull(source, "来源位置不能为空");
        Objects.requireNonNull(text, "正文不能为空");
    }
}
