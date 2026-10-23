package jobapply.api;

import java.util.Map;
import java.util.NoSuchElementException;
import jobapply.config.ModelRegistry;
import jobapply.config.SettingsException;
import jobapply.memory.ImportRunner;
import jobapply.memory.StructuredCall;
import jobapply.persistence.EnvFile;
import jobapply.persistence.ModelSettingsFile;
import jobapply.tools.GitHubPublicReader;
import jobapply.workflow.ApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * 错误统一返回 {@code {"error": "<原因>"}}，不吞掉，也不返回 200。原因里不会有 Key（各处的异常信息只带变量名）。
 */
@RestControllerAdvice
class ApiErrors {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApplicationException.NotFound.class)
    ResponseEntity<Map<String, String>> notFound(ApplicationException.NotFound e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ApplicationException.Conflict.class)
    ResponseEntity<Map<String, String>> conflict(ApplicationException.Conflict e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(ApplicationException.BadRequest.class)
    ResponseEntity<Map<String, String>> applicationBadRequest(ApplicationException.BadRequest e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler({SettingsException.NotFound.class, NoSuchElementException.class})
    ResponseEntity<Map<String, String>> settingNotFound(RuntimeException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({SettingsException.Conflict.class, ModelSettingsFile.InUseException.class,
            ModelRegistry.NotConfiguredException.class})
    ResponseEntity<Map<String, String>> settingConflict(RuntimeException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(SettingsException.Upstream.class)
    ResponseEntity<Map<String, String>> upstream(SettingsException.Upstream e) {
        return error(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(ImportRunner.StateException.class)
    ResponseEntity<Map<String, String>> importState(ImportRunner.StateException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 整理、起草译文、生成画像时模型调用失败或返回的 JSON 不对。 */
    @ExceptionHandler(StructuredCall.StepFailed.class)
    ResponseEntity<Map<String, String>> modelStep(StructuredCall.StepFailed e) {
        return error(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(EnvFile.MissingVariableException.class)
    ResponseEntity<Map<String, String>> missingKey(EnvFile.MissingVariableException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage() + "，先填 Key");
    }

    @ExceptionHandler(GitHubPublicReader.GitHubException.class)
    ResponseEntity<Map<String, String>> github(GitHubPublicReader.GitHubException e) {
        HttpStatus status = switch (e.kind()) {
            case NOT_FOUND, EMPTY -> HttpStatus.BAD_REQUEST;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case UPSTREAM -> HttpStatus.BAD_GATEWAY;
        };
        return error(status, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException e) {
        return error(HttpStatus.CONTENT_TOO_LARGE, "文件超过 20MB");
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    ResponseEntity<Map<String, String>> badUpload(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "上传请求不完整：需要 multipart/form-data 的 file 字段");
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
