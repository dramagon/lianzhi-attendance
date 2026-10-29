package cn.lianzhi.attendance.knowledge.retrieval;

/** Small integration workspace only, not a production datastore. Tombstones consume document slots. */
public record WorkspaceLimits(int documents, int chunks, int textCodePoints, int vectorValues) {
    public WorkspaceLimits {
        if (documents < 1 || chunks < 1 || textCodePoints < 1 || vectorValues < 1)
            throw new IllegalArgumentException("工作区上限必须为正");
    }
    public static WorkspaceLimits defaults() { return new WorkspaceLimits(100, 20_000, 4_000_000, 1_000_000); }
}
