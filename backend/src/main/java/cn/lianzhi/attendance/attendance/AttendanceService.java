package cn.lianzhi.attendance.attendance;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class AttendanceService {
    private final ObjectProvider<AttendanceDirectory> directories;
    private final AttendanceStore store;
    private final QrTokens tokens;
    private final Clock clock;
    private final cn.lianzhi.attendance.face.FaceAttendancePort faces;
    @org.springframework.beans.factory.annotation.Autowired
    public AttendanceService(ObjectProvider<AttendanceDirectory> directories, AttendanceStore store, QrTokens tokens,
                             cn.lianzhi.attendance.face.FaceAttendancePort faces) {
        this(directories, store, tokens, Clock.systemUTC(),faces);
    }
    AttendanceService(ObjectProvider<AttendanceDirectory> directories, AttendanceStore store, QrTokens tokens, Clock clock) {
        this(directories,store,tokens,clock,null);
    }
    AttendanceService(ObjectProvider<AttendanceDirectory> directories, AttendanceStore store, QrTokens tokens, Clock clock,
                      cn.lianzhi.attendance.face.FaceAttendancePort faces) {
        this.directories = directories; this.store = store; this.tokens = tokens; this.clock = clock; this.faces=faces;
    }
    public record Capability(boolean available, boolean rulesAvailable, boolean directoryAvailable,
                             boolean storageAvailable, String timeZone, Instant serverTime, List<String> blockers) {}
    public record Punch(String type, String token, String requestId, GeoFence.Point location, Double accuracyMeters, String faceVerificationId) {
        public Punch(String type,String token,String requestId,GeoFence.Point location,Double accuracyMeters) {
            this(type,token,requestId,location,accuracyMeters,null);
        }
    }
    public record EventView(long id, long workplaceId, LocalDate businessDate, String type, Instant occurredAt, double distanceMeters) {
        public static EventView from(AttendanceStore.Event e) {
            return new EventView(e.id(), e.workplaceId(), e.businessDate(), e.type(), e.occurredAt(), e.distanceMeters());
        }
    }
    public record Records(List<EventView> items, long total, int page, int size) {}
    public record PunchReceipt(boolean found, EventView event) {}
    /** Read-only recovery after a lost response; never replays a punch or trusts a client user ID. */
    public PunchReceipt receipt(long userId, String requestId) {
        required();
        if (requestId == null || !requestId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("请求编号无效，请查看本人考勤记录");
        return store.byRequest(userId, requestId).map(e -> new PunchReceipt(true, EventView.from(e)))
            .orElseGet(() -> new PunchReceipt(false, null));
    }
    public Capability capability() {
        boolean directory = directories.getIfAvailable() != null, storage = store.ready();
        List<String> blockers = new ArrayList<>();
        if (!directory) blockers.add("等待 A 模块提供员工/场所绑定、公司权限范围与班次分配接口");
        if (!storage) blockers.add("等待 A 的 V2 合并后，按顺序启用 B 的 V3 考勤迁移");
        return new Capability(directory && storage, true, directory, storage, AttendanceRules.ZONE.getId(), clock.instant(), blockers);
    }
    private AttendanceDirectory required() {
        AttendanceDirectory directory = directories.getIfAvailable();
        if (directory == null || !store.ready()) throw AttendanceProblem.unavailable("组织、场所或考勤存储尚未接入，暂不能操作真实考勤");
        return directory;
    }
    public List<AttendanceDirectory.Workplace> workplaces(long userId) { return required().workplaces(userId); }
    public List<AttendanceDirectory.Workplace> managedWorkplaces(long userId) { return required().managedWorkplaces(userId); }
    public QrTokens.Issued issue(long managerId, long workplaceId) {
        required().requireManager(managerId, workplaceId);
        return tokens.issue(workplaceId);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public EventView punch(long userId, Punch request) {
        AttendanceDirectory directory = required();
        if (request == null || !Set.of("IN", "OUT").contains(Objects.toString(request.type, ""))) throw new IllegalArgumentException("请选择上班或下班打卡");
        try { UUID.fromString(request.requestId); } catch (RuntimeException e) { throw new IllegalArgumentException("请求编号无效，请刷新后重试"); }
        QrTokens.Verified qr = tokens.verify(request.token);
        AttendanceDirectory.Assignment assignment = directory.forPunch(userId, qr.workplaceId(), clock.instant());
        if (assignment == null || assignment.workplace() == null || assignment.rule() == null || assignment.businessDate() == null
                || assignment.workplace().id() != qr.workplaceId()) throw AttendanceProblem.unavailable("员工尚未分配有效场所与班次");
        if (request.location == null || request.accuracyMeters == null) throw new IllegalArgumentException("请先获取有效定位");
        double distance = GeoFence.check(request.location, assignment.workplace().point(), request.accuracyMeters, assignment.workplace().radiusMeters());
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), AttendanceRules.ZONE);
        LocalDateTime start = assignment.businessDate().atTime(assignment.rule().start());
        LocalDateTime end = AttendanceRules.finish(assignment.businessDate(), assignment.rule().start(), assignment.rule().end());
        if (now.isBefore(start.minusHours(12)) || now.isAfter(end.plusHours(12))) throw new IllegalArgumentException("当前时间不在分配班次的打卡窗口内");
        if (assignment.holiday() || !assignment.rule().workdays().contains(assignment.businessDate().getDayOfWeek()))
            throw new IllegalArgumentException("当前为休息日，请先通过加班流程安排出勤");
        store.lockUser(userId);
        Optional<AttendanceStore.Event> replay = store.byRequest(userId, request.requestId);
        if (replay.isPresent()) {
            if (!replay.get().type().equals(request.type) || replay.get().workplaceId() != qr.workplaceId())
                throw new IllegalArgumentException("请求编号已用于另一条打卡，请重新发起");
            return EventView.from(replay.get());
        }
        List<AttendanceStore.Event> events = store.day(userId, assignment.businessDate());
        if (events.stream().anyMatch(e -> e.type().equals(request.type)))
            throw new AttendanceProblem("DUPLICATE_PUNCH", "本班次已完成该类型打卡，请勿重复提交", org.springframework.http.HttpStatus.CONFLICT);
        var adjustedIn=directory.adjustedPunch(userId,assignment.businessDate(),"IN");
        var adjustedSame=directory.adjustedPunch(userId,assignment.businessDate(),request.type);
        if(adjustedSame!=null)throw new AttendanceProblem("DUPLICATE_PUNCH","该方向已通过补卡完成，请刷新日记录",org.springframework.http.HttpStatus.CONFLICT);
        if(adjustedIn!=null && !now.isAfter(adjustedIn))throw new IllegalArgumentException("签退须晚于已审批的签到时间");
        if ("OUT".equals(request.type) && adjustedIn==null && events.stream().noneMatch(e -> e.type().equals("IN")))
            throw new IllegalArgumentException("请先完成本班次上班打卡");
        if (events.stream().anyMatch(e -> !clock.instant().isAfter(e.occurredAt()))) throw new IllegalArgumentException("签退时间须晚于签到");
        if(assignment.workplace().faceRequired() || (faces!=null && faces.requiredForPunch()) || request.faceVerificationId!=null) {
            if(faces==null) throw AttendanceProblem.unavailable("人脸验证能力尚未接入，不能校验本次人脸凭据");
            faces.consume(userId,qr.workplaceId(),qr.nonce(),request.requestId,request.type,request.faceVerificationId);
        }
        return EventView.from(store.append(userId, qr.workplaceId(), assignment.businessDate(), request.type, clock.instant(), request.requestId, qr.nonce(), distance));
    }
    public Records records(long userId, LocalDate from, LocalDate to, int page, int size) {
        required();
        if (from == null || to == null || to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 93)
            throw new IllegalArgumentException("查询开始与结束日期须有效，范围最多 94 天");
        if (page < 1 || page > 100000 || size < 1 || size > 100) throw new IllegalArgumentException("页码从 1 开始，每页 1–100 条");
        AttendanceStore.Page result = store.page(userId, from, to, page, size);
        return new Records(result.items().stream().map(EventView::from).toList(), result.total(), page, size);
    }
}
