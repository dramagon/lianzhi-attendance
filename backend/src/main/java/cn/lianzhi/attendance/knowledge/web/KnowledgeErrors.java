package cn.lianzhi.attendance.knowledge.web;
import cn.lianzhi.attendance.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MissingServletRequestParameterException;
@RestControllerAdvice(assignableTypes=KnowledgeStorageController.class)
public class KnowledgeErrors {
    @ExceptionHandler({IllegalArgumentException.class,MissingServletRequestParameterException.class})
    ResponseEntity<?> invalid(Exception error){return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_INPUT","知识库参数无效，请检查必填字段和版本"));}
}
