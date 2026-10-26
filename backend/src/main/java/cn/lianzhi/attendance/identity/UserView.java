package cn.lianzhi.attendance.identity;

import java.time.LocalDateTime;
import java.util.List;

public record UserView(Long id, String username, String displayName, String role, String roleName,
                       String employeeNo, String departmentName, Boolean enabled,
                       LocalDateTime createdAt, List<String> permissions, String avatarUrl) {
    public static UserView from(UserAccount account) {
        String name = switch (account.role()) {
            case "ADMIN" -> "系统管理员";
            case "HR" -> "人事专员";
            default -> "员工";
        };
        List<String> permissions = switch (account.role()) {
            case "ADMIN" -> List.of("dashboard:management", "accounts:read", "organization:manage", "profile:read", "roadmap:read");
            case "HR" -> List.of("dashboard:management", "organization:manage", "profile:read", "roadmap:read");
            default -> List.of("dashboard:employee", "profile:read", "roadmap:read");
        };
        return new UserView(account.id(), account.username(), account.displayName(), account.role(),
                name, account.employeeNo(), account.departmentName(), account.enabled(), account.createdAt(), permissions, null);
    }
    public UserView withAvatar(String avatarUrl) {
        return new UserView(id,username,displayName,role,roleName,employeeNo,departmentName,enabled,createdAt,permissions,avatarUrl);
    }
}
