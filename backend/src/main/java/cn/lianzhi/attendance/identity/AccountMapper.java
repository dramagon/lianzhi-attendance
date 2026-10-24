package cn.lianzhi.attendance.identity;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface AccountMapper {
    String COLUMNS = "u.id, u.username, u.password_hash, u.display_name, u.role, u.employee_no, "
            + "u.department_id, d.name AS department_name, u.enabled, u.created_at";

    @Select("SELECT " + COLUMNS + " FROM sys_user u LEFT JOIN sys_department d ON d.id=u.department_id WHERE u.username=#{username}")
    UserAccount findByUsername(String username);

    @Select("SELECT " + COLUMNS + " FROM sys_user u LEFT JOIN sys_department d ON d.id=u.department_id ORDER BY u.id")
    List<UserAccount> findAll();

    @Select("SELECT COUNT(*) FROM sys_user")
    long count();

    @Select("SELECT COUNT(*) FROM sys_user WHERE enabled=TRUE")
    long countEnabled();

    @Select("SELECT COUNT(DISTINCT role) FROM sys_user")
    long countRoles();

    @Select("SELECT COUNT(*) FROM sys_department")
    long countDepartments();

    @Insert("INSERT INTO sys_user (username,password_hash,display_name,role,employee_no,department_id) "
            + "VALUES (#{username},#{passwordHash},#{displayName},#{role},#{employeeNo},#{departmentId})")
    void insert(@Param("username") String username, @Param("passwordHash") String passwordHash,
                @Param("displayName") String displayName, @Param("role") String role,
                @Param("employeeNo") String employeeNo, @Param("departmentId") Long departmentId);
}
