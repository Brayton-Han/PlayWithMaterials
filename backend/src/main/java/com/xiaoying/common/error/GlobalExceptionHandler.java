package com.xiaoying.common.error;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 把所有异常转为统一的 {@link ErrorResponse}（architecture §3.1 第 4 条、api.yaml 统一错误格式）。
 * 未预期异常只记日志，响应中不包含堆栈或异常信息。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final DataSize maxFileSize;
    private final DataSize maxRequestSize;

    public GlobalExceptionHandler(
            @Value("${spring.servlet.multipart.max-file-size:5MB}") DataSize maxFileSize,
            @Value("${spring.servlet.multipart.max-request-size:20MB}") DataSize maxRequestSize) {
        this.maxFileSize = maxFileSize;
        this.maxRequestSize = maxRequestSize;
    }

    // ---------- 业务异常 ----------

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return respond(ex.code(), ex.getMessage(), ex.details());
    }

    // ---------- 400 ----------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException ex) {
        List<FieldErrorDto> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toFieldError)
                .toList();
        return validationFailed(fieldErrors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleParamValidation(HandlerMethodValidationException ex) {
        List<FieldErrorDto> fieldErrors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> FieldErrorDto.of(
                                result.getMethodParameter().getParameterName(),
                                fieldCode(lastCode(error)),
                                error.getDefaultMessage())))
                .toList();
        return validationFailed(fieldErrors);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        return validationFailed(List.of(FieldErrorDto.of(ex.getParameterName(), "REQUIRED", "缺少参数 " + ex.getParameterName())));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException ex) {
        return validationFailed(List.of(FieldErrorDto.of(ex.getRequestPartName(), "REQUIRED", "缺少参数 " + ex.getRequestPartName())));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return validationFailed(List.of(FieldErrorDto.of(ex.getName(), "FORMAT", "参数 " + ex.getName() + " 格式不正确")));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return respond(ErrorCode.VALIDATION_FAILED, "请求体格式不正确", null);
    }

    // ---------- 404 / 405 / 413 / 415 ----------

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception ex) {
        return respond(ErrorCode.NOT_FOUND, null, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED, null, null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("maxFileSizeBytes", maxFileSize.toBytes());
        details.put("maxRequestSizeBytes", maxRequestSize.toBytes());
        String message = "上传内容过大，单张图片不超过 %d MB，总大小不超过 %d MB"
                .formatted(maxFileSize.toMegabytes(), maxRequestSize.toMegabytes());
        return respond(ErrorCode.PAYLOAD_TOO_LARGE, message, details);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE, null, null);
    }

    // ---------- 兜底 500 ----------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(ErrorCode.INTERNAL_ERROR, null, null);
    }

    // ---------- helpers ----------

    private ResponseEntity<ErrorResponse> validationFailed(List<FieldErrorDto> fieldErrors) {
        Map<String, Object> details = fieldErrors.isEmpty() ? null : Map.of("fieldErrors", fieldErrors);
        return respond(ErrorCode.VALIDATION_FAILED, null, details);
    }

    private static ResponseEntity<ErrorResponse> respond(ErrorCode code, String message, Map<String, Object> details) {
        String text = (message == null || message.isBlank()) ? code.defaultMessage() : message;
        return ResponseEntity.status(code.status()).body(new ErrorResponse(code, text, details));
    }

    private static FieldErrorDto toFieldError(FieldError error) {
        return FieldErrorDto.of(error.getField(), fieldCode(error.getCode()), error.getDefaultMessage());
    }

    private static String lastCode(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        return (codes == null || codes.length == 0) ? null : codes[codes.length - 1];
    }

    /** Bean Validation 约束名 → api.yaml FieldError.code。 */
    static String fieldCode(String constraint) {
        if (constraint == null) {
            return "INVALID_VALUE";
        }
        return switch (constraint) {
            case "NotNull", "NotBlank", "NotEmpty" -> "REQUIRED";
            case "Pattern" -> "PATTERN";
            case "Size", "Length" -> "LENGTH";
            case "Email" -> "FORMAT";
            case "Min", "Max", "DecimalMin", "DecimalMax", "Positive", "PositiveOrZero", "Range" -> "OUT_OF_RANGE";
            default -> "INVALID_VALUE";
        };
    }
}
