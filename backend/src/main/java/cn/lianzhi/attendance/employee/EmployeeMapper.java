package cn.lianzhi.attendance.employee;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface EmployeeMapper {
    String FROM = " FROM sys_user u LEFT JOIN org_company c ON c.id=u.company_id LEFT JOIN sys_department d ON d.id=u.department_id ";
    String COLUMNS = "u.id AS user_id,u.username,u.employee_no,u.display_name,u.role,u.company_id,c.name AS company_name,u.department_id,d.name AS department_name,u.enabled,u.created_at";
    String FILTER = " WHERE (#{companyId} IS NULL OR u.company_id=#{companyId}) AND (#{ownId} IS NULL OR u.id=#{ownId}) AND (#{query}='' OR LOWER(u.username) LIKE #{query} OR LOWER(u.display_name) LIKE #{query} OR LOWER(u.employee_no) LIKE #{query})";
    @Select("SELECT " + COLUMNS + FROM + FILTER + " ORDER BY u.id LIMIT #{size} OFFSET #{offset}")
    List<EmployeeView> list(Long companyId, Long ownId, String query, int size, int offset);
    @Select("SELECT COUNT(*)" + FROM + FILTER)
    long count(Long companyId, Long ownId, String query);
    @Select("SELECT COUNT(*) FROM sys_user WHERE company_id=#{companyId} AND enabled=TRUE")
    long countEnabled(Long companyId);
    @Select("SELECT COUNT(DISTINCT role) FROM sys_user WHERE company_id=#{companyId}")
    long countRoles(Long companyId);
    @Select("SELECT " + COLUMNS + FROM + " WHERE u.id=#{id}")
    EmployeeView find(Long id);
    @Select("SELECT id FROM sys_user ORDER BY id FOR UPDATE")
    List<Long> lockUsers();
    @Select("SELECT COUNT(*) FROM sys_user WHERE role='ADMIN' AND enabled=TRUE")
    long enabledAdmins();
    @Insert("INSERT INTO sys_user(username,password_hash,display_name,role,employee_no,company_id,department_id,enabled) VALUES(#{username},#{hash},#{input.displayName},#{input.role},#{input.employeeNo},#{input.companyId},#{input.departmentId},#{input.enabled})")
    void insert(String username, String hash, EmployeeService.EmployeeInput input);
    @Update("UPDATE sys_user SET display_name=#{input.displayName},employee_no=#{input.employeeNo},role=#{input.role},company_id=#{input.companyId},department_id=#{input.departmentId},enabled=#{input.enabled},password_hash=COALESCE(#{hash},password_hash),auth_version=auth_version+1 WHERE id=#{id}")
    void update(Long id, String hash, EmployeeService.EmployeeInput input);
    @Delete("DELETE FROM employee_workplace WHERE user_id=#{userId}")
    void clearWorkplaces(Long userId);
    @Insert("INSERT INTO employee_workplace(user_id,workplace_id) VALUES(#{userId},#{workplaceId})")
    void bind(Long userId, Long workplaceId);
}
