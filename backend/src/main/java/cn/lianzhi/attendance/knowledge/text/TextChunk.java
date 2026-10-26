package cn.lianzhi.attendance.knowledge.text;

import java.util.Objects;

/** Offsets are [start,end) code points in the normalized source section, not original file bytes. */
public record TextChunk(int sequence, int sectionIndex, SourceLocation source,
                        int startOffset, int endOffset, String content) {
    public TextChunk {
        Objects.requireNonNull(source, "来源位置不能为空");
        Objects.requireNonNull(content, "切片正文不能为空");
        if (sequence < 1 || sectionIndex < 1 || startOffset < 0 || endOffset <= startOffset
                || content.codePointCount(0, content.length()) != endOffset - startOffset) {
            throw new IllegalArgumentException("切片序号或正文偏移无效");
        }
    }
}
