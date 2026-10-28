package cn.lianzhi.attendance.knowledge.retrieval;

import java.util.Objects;

public record DocumentAccess(long companyId, long ownerUserId, Visibility visibility) {
    public enum Visibility { PRIVATE, COMPANY }
    public DocumentAccess {
        if (companyId < 1 || ownerUserId < 1 || visibility == null)
            throw new IllegalArgumentException("文档范围无效");
    }
    public boolean readableBy(KnowledgeActor actor) {
        Objects.requireNonNull(actor);
        return actor.role() == KnowledgeActor.Role.ADMIN ||
                (Objects.equals(actor.companyId(), companyId) &&
                        (actor.role() == KnowledgeActor.Role.HR || visibility == Visibility.COMPANY || actor.userId() == ownerUserId));
    }
    public boolean manageableBy(KnowledgeActor actor) {
        return readableBy(actor) && (actor.role() != KnowledgeActor.Role.EMPLOYEE ||
                (actor.userId() == ownerUserId && visibility == Visibility.PRIVATE));
    }
}
