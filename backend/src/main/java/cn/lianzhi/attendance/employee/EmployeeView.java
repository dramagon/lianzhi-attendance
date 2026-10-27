package cn.lianzhi.attendance.employee;

import java.time.LocalDateTime;

/** 对外共享DTO，不含密码和认证版本。员工身份始终为sys_user.id。 */
public record EmployeeView(Long userId, String username, String employeeNo, String displayName,
                           String role, Long companyId, String companyName, Long departmentId,
                           String departmentName, Boolean enabled, LocalDateTime createdAt) {}
