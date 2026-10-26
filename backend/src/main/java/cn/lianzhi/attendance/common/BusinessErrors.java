package cn.lianzhi.attendance.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class BusinessErrors {
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> business(BusinessException error) {
        return ResponseEntity.status(error.status()).body(ApiResponse.error(error.status().name(), error.getMessage()));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiResponse<Void>> conflict(DataIntegrityViolationException error) {
        return ResponseEntity.status(409).body(ApiResponse.error("CONFLICT", "记录重复或仍有业务引用，请刷新后检查"));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiResponse<Void>> invalid(Exception error) {
        return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_INPUT", "参数格式不正确"));
    }
}
