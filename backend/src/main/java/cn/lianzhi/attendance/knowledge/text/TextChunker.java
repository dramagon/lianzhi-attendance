package cn.lianzhi.attendance.knowledge.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Deterministic preparation only: no parsing, retrieval, embedding, database or authorization. */
public final class TextChunker {
    private final ChunkingOptions options;

    public TextChunker() { this(ChunkingOptions.defaults()); }
    public TextChunker(ChunkingOptions options) { this.options = Objects.requireNonNull(options); }

    public List<TextChunk> chunk(List<TextSection> sections) {
        Objects.requireNonNull(sections, "文档段落不能为空");
        if (sections.size() > options.maxSections()) throw new IllegalArgumentException("文档来源段落数量超限");
        // Check the complete input before producing any partial output, including blank/removed characters.
        long documentSize = 0;
        for (TextSection section : sections) {
            Objects.requireNonNull(section, "来源段落不能为空");
            documentSize += section.text().codePointCount(0, section.text().length());
            if (documentSize > options.maxDocumentCodePoints()) throw new IllegalArgumentException("文档正文长度超限");
        }
        List<TextChunk> chunks = new ArrayList<>();
        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            TextSection section = sections.get(sectionIndex);
            int[] points = TextNormalizer.normalize(section.text()).codePoints().toArray();
            int start = 0;
            while (start < points.length) {
                int end = start + Math.min(options.maxChunkCodePoints(), points.length - start);
                if (end < points.length) end = preferredEnd(points, start, end);
                if (chunks.size() >= options.maxChunks()) throw new IllegalArgumentException("文档切片数量超限");
                chunks.add(new TextChunk(chunks.size() + 1, sectionIndex + 1, section.source(), start, end,
                        new String(points, start, end - start)));
                if (end == points.length) break;
                start = end - options.overlapCodePoints();
            }
        }
        return List.copyOf(chunks);
    }

    private int preferredEnd(int[] points, int start, int hardEnd) {
        // A chosen boundary must advance past the overlap; even a huge overlap must terminate.
        int minimum = start + Math.max(options.overlapCodePoints() + 1, options.maxChunkCodePoints() / 2);
        for (int end = hardEnd; end >= minimum; end--) {
            if (end >= 2 && points[end - 1] == '\n' && points[end - 2] == '\n') return end;
        }
        for (int end = hardEnd; end >= minimum; end--) {
            if (isBoundary(points[end - 1])) return end;
        }
        return hardEnd;
    }

    private static boolean isBoundary(int point) {
        return point == '\n' || point == '。' || point == '！' || point == '？' || point == '；'
                || point == '.' || point == '!' || point == '?' || point == ';';
    }
}
