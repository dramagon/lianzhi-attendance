package cn.lianzhi.attendance.organization;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface OrganizationMapper {
    record Company(Long id, String name, Boolean enabled) {}
    record Department(Long id, Long companyId, String name, Boolean enabled) {}
    @Select("SELECT id,name,enabled FROM org_company WHERE (#{companyId} IS NULL OR id=#{companyId}) ORDER BY id LIMIT #{size} OFFSET #{offset}")
    List<Company> companies(Long companyId, int size, int offset);
    @Select("SELECT COUNT(*) FROM org_company WHERE (#{companyId} IS NULL OR id=#{companyId})")
    long countCompanies(Long companyId);
    @Select("SELECT id,name,enabled FROM org_company WHERE id=#{id}")
    Company company(Long id);
    @Select("SELECT id FROM org_company WHERE id=#{id} FOR UPDATE")
    Long lockCompany(Long id);
    @Insert("INSERT INTO org_company(name,enabled) VALUES(#{name},#{enabled})")
    void insertCompany(String name, boolean enabled);
    @Update("UPDATE org_company SET name=#{name},enabled=#{enabled} WHERE id=#{id}")
    void updateCompany(Long id, String name, boolean enabled);
    @Select("SELECT (SELECT COUNT(*) FROM sys_user WHERE company_id=#{id} AND enabled=TRUE) + (SELECT COUNT(*) FROM sys_department WHERE company_id=#{id} AND enabled=TRUE) + (SELECT COUNT(*) FROM workplace WHERE company_id=#{id} AND enabled=TRUE)")
    long companyReferences(Long id);
    @Select("SELECT id,company_id,name,enabled FROM sys_department WHERE (#{companyId} IS NULL OR company_id=#{companyId}) ORDER BY id LIMIT #{size} OFFSET #{offset}")
    List<Department> departments(Long companyId, int size, int offset);
    @Select("SELECT COUNT(*) FROM sys_department WHERE (#{companyId} IS NULL OR company_id=#{companyId})")
    long countDepartments(Long companyId);
    @Select("SELECT id,company_id,name,enabled FROM sys_department WHERE id=#{id}")
    Department department(Long id);
    @Insert("INSERT INTO sys_department(company_id,name,enabled) VALUES(#{companyId},#{name},#{enabled})")
    void insertDepartment(Long companyId, String name, boolean enabled);
    @Update("UPDATE sys_department SET name=#{name},enabled=#{enabled} WHERE id=#{id}")
    void updateDepartment(Long id, String name, boolean enabled);
    @Select("SELECT COUNT(*) FROM sys_user WHERE department_id=#{id} AND enabled=TRUE")
    long departmentReferences(Long id);
}
