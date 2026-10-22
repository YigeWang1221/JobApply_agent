package jobapply.api;

import java.util.Map;
import jobapply.workflow.graphs.LoginHandoffException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 错误统一返回 {@code {"error": "<原因>"}}，不吞掉，也不返回 200。
 */
@RestControllerAdvice
class ApiErrors {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(LoginHandoffException.NotFound.class)
    ResponseEntity<Map<String, String>> notFound(LoginHandoffException.NotFound e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(LoginHandoffException.Conflict.class)
    ResponseEntity<Map<String, String>> conflict(LoginHandoffException.Conflict e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "请求体不是合法的 JSON");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, String>> mediaType(HttpMediaTypeNotSupportedException e) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "写接口只接受 Content-Type: application/json");
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, String>> unexpected(RuntimeException e) {
        log.error("接口出错", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? status.getReasonPhrase() : message));
    }
}
