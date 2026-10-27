package cn.lianzhi.attendance.attendance;

import cn.lianzhi.attendance.common.ApiResponse;
import cn.lianzhi.attendance.face.FaceController;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;

/** Advice is scoped to B only: existing authentication/error contracts stay intact. */
@org.springframework.core.annotation.Order(-1)
@RestControllerAdvice(assignableTypes = {AttendanceController.class, AttendancePlanningController.class, FaceController.class})
public class AttendanceErrors {
    @ExceptionHandler(AttendanceProblem.class)
    ResponseEntity<ApiResponse<Void>> problem(AttendanceProblem e) {
        return ResponseEntity.status(e.status()).body(ApiResponse.error(e.code(), e.getMessage()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Void>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error("ATTENDANCE_INVALID_INPUT", e.getMessage()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ApiResponse<Void>> malformed(Exception e) {
        String message = "请检查日期、时间、数值和必填字段格式";
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof AttendanceRules.InvalidRule) { message = cause.getMessage(); break; }
        }
        return ResponseEntity.badRequest().body(ApiResponse.error("ATTENDANCE_INVALID_INPUT", message));
    }
}
