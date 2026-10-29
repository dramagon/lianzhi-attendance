package cn.lianzhi.attendance.knowledge.text;

/** Limits apply to the whole document; Unicode code points are not model tokens. */
public record ChunkingOptions(int maxChunkCodePoints, int overlapCodePoints, int maxDocumentCodePoints,
                              int maxSections, int maxChunks) {
    public ChunkingOptions {
        if (maxChunkCodePoints < 1 || overlapCodePoints < 0 || overlapCodePoints >= maxChunkCodePoints
                || maxDocumentCodePoints < 1 || maxSections < 1 || maxChunks < 1) {
            throw new IllegalArgumentException("切片长度和文档上限必须为正，重叠长度必须非负且小于切片长度");
        }
    }

    public static ChunkingOptions defaults() { return new ChunkingOptions(800, 120, 2_000_000, 10_000, 10_000); }
}
