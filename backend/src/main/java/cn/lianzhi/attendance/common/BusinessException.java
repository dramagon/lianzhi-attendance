package cn.lianzhi.attendance.common;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {
    private final HttpStatus status;
    public BusinessException(HttpStatus status, String message) { super(message); this.status = status; }
    public HttpStatus status() { return status; }
    public static BusinessException invalid(String message) { return new BusinessException(HttpStatus.BAD_REQUEST, message); }
    public static BusinessException forbidden() { return new BusinessException(HttpStatus.FORBIDDEN, "无权访问此公司或员工"); }
    public static BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND, "记录不存在"); }
    public static BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT, message); }
}
