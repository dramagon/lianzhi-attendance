package cn.lianzhi.attendance.attendance;

import org.springframework.http.HttpStatus;

public class AttendanceProblem extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    public AttendanceProblem(String code, String message, HttpStatus status) {
        super(message); this.code = code; this.status = status;
    }
    public String code() { return code; }
    public HttpStatus status() { return status; }
    public static AttendanceProblem unavailable(String message) {
        return new AttendanceProblem("ATTENDANCE_NOT_READY", message, HttpStatus.SERVICE_UNAVAILABLE);
    }
}
