package cn.lianzhi.attendance.assistant;

import cn.lianzhi.attendance.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;

/** C's HTTP boundary maps B's Java input errors without changing A/B's exception handling. */
@RestControllerAdvice(assignableTypes=AssistantController.class)
public class AssistantErrors {
    @ExceptionHandler({IllegalArgumentException.class,MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse<Void>> invalid(Exception ignored){
        return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_INPUT","查询参数无效，请检查日期范围和分页"));
    }
}
