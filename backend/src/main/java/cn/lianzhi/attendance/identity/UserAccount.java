package cn.lianzhi.attendance.identity;

import java.time.LocalDateTime;

/** 仅在持久层和认证流程内使用，禁止直接返回给客户端。 */
public record UserAccount(Long id, String username, String passwordHash, String displayName,
                          String role, String employeeNo, Long departmentId, String departmentName,
                          Boolean enabled, LocalDateTime createdAt) {
}
