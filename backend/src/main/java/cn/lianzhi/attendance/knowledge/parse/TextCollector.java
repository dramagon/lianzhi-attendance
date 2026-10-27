package cn.lianzhi.attendance.knowledge.parse;

import cn.lianzhi.attendance.knowledge.text.*;
import java.io.Writer;
import java.util.*;

/** Checks limits before appending; failures never expose partial sections. */
final class TextCollector {
    final ParseOptions options;
    private final List<TextSection> sections = new ArrayList<>();
    private int points;
    private int cells;
    TextCollector(ParseOptions options) { this.options = options; }
    void cell() { ParseFailure.limit(++cells > options.maxCells()); }
    SectionWriter section(SourceLocation source) {
        ParseFailure.limit(sections.size() >= options.maxSections());
        return new SectionWriter(source);
    }
    List<TextSection> finish() { return List.copyOf(sections); }
    final class SectionWriter extends Writer {
        private final SourceLocation source;
        private final StringBuilder text = new StringBuilder();
        private boolean previousHigh;
        SectionWriter(SourceLocation source) { this.source = source; }
        @Override public void write(char[] buffer, int offset, int length) {
            for (int i = offset; i < offset + length; i++) {
                char ch = buffer[i];
                if (!(previousHigh && Character.isLowSurrogate(ch))) {
                    ParseFailure.limit(points >= options.maxCodePoints()); points++;
                }
                text.append(ch); previousHigh = Character.isHighSurrogate(ch);
            }
        }
        void add(String value) { write(value.toCharArray(), 0, value.length()); }
        @Override public void flush() {}
        @Override public void close() { sections.add(new TextSection(source, text.toString())); }
    }
}
