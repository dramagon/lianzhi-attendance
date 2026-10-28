package cn.lianzhi.attendance.knowledge.retrieval;

/** Trusted internal identity. Future bridge must resolve it from authentication and A's scope service,
 * on every operation. Never bind this record from HTTP input or model/tool arguments. */
public record KnowledgeActor(long userId, Role role, Long companyId) {
    public enum Role { ADMIN, HR, EMPLOYEE }
    public KnowledgeActor {
        if (userId < 1 || role == null || (companyId != null && companyId < 1))
            throw new IllegalArgumentException("知识库身份无效");
    }
}
