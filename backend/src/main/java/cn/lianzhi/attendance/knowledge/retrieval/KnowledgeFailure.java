package cn.lianzhi.attendance.knowledge.retrieval;

/** Internal safe errors; inaccessible and missing document IDs intentionally share NOT_FOUND. */
public final class KnowledgeFailure extends RuntimeException {
    public enum Code { INVALID_INPUT, FORBIDDEN, NOT_FOUND, CONFLICT, LIMIT_EXCEEDED, UNAVAILABLE }
    private final Code code;
    public KnowledgeFailure(Code code, String message) { super(message); this.code = code; }
    public Code code() { return code; }
}
