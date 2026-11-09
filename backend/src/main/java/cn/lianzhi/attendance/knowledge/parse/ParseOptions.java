package cn.lianzhi.attendance.knowledge.parse;

/** Positive per-document limits. Null options are rejected by the parser constructor. */
public record ParseOptions(int maxBytes, int maxCodePoints, int maxSections, int maxPages,
                           int maxCells, int maxZipEntries, int maxZipEntryBytes, int maxZipTotalBytes) {
    public ParseOptions {
        if (maxBytes < 1 || maxCodePoints < 1 || maxSections < 1 || maxPages < 1 || maxCells < 1
                || maxZipEntries < 1 || maxZipEntryBytes < 1 || maxZipTotalBytes < 1)
            throw new IllegalArgumentException("解析上限必须为正数");
    }
    public static ParseOptions defaults() {
        return new ParseOptions(10 * 1024 * 1024, 2_000_000, 10_000, 1_000,
                200_000, 2_000, 16 * 1024 * 1024, 64 * 1024 * 1024);
    }
}
