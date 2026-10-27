package cn.lianzhi.attendance.workplace;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface WorkplaceMapper {
    String COLUMNS = "w.id,w.company_id,w.name,w.latitude,w.longitude,w.coordinate_system,w.radius_meters,w.enabled";
    String FILTER = " FROM workplace w WHERE (#{companyId} IS NULL OR w.company_id=#{companyId}) AND (#{ownId} IS NULL OR (w.enabled=TRUE AND EXISTS(SELECT 1 FROM employee_workplace b JOIN sys_user u ON u.id=b.user_id JOIN org_company c ON c.id=u.company_id WHERE b.workplace_id=w.id AND b.user_id=#{ownId} AND u.enabled=TRUE AND c.enabled=TRUE AND u.company_id=w.company_id)))";
    @Select("SELECT " + COLUMNS + FILTER + " ORDER BY w.id LIMIT #{size} OFFSET #{offset}")
    List<WorkplaceView> list(Long companyId, Long ownId, int size, int offset);
    @Select("SELECT COUNT(*)" + FILTER)
    long count(Long companyId, Long ownId);
    @Select("SELECT " + COLUMNS + " FROM workplace w WHERE w.id=#{id}")
    WorkplaceView find(Long id);
    @Select("SELECT " + COLUMNS + " FROM workplace w JOIN employee_workplace b ON b.workplace_id=w.id WHERE b.user_id=#{userId} ORDER BY w.id")
    List<WorkplaceView> forEmployee(Long userId);
    @Select("SELECT COUNT(*) FROM employee_workplace b JOIN sys_user u ON u.id=b.user_id WHERE b.workplace_id=#{id} AND u.enabled=TRUE")
    long activeReferences(Long id);
    @Insert("INSERT INTO workplace(company_id,name,latitude,longitude,coordinate_system,radius_meters,enabled) VALUES(#{companyId},#{name},#{latitude},#{longitude},#{coordinateSystem},#{radiusMeters},#{enabled})")
    void insert(WorkplaceService.WorkplaceInput input);
    @Update("UPDATE workplace SET name=#{input.name},latitude=#{input.latitude},longitude=#{input.longitude},coordinate_system=#{input.coordinateSystem},radius_meters=#{input.radiusMeters},enabled=#{input.enabled} WHERE id=#{id}")
    void update(Long id, WorkplaceService.WorkplaceInput input);
}
