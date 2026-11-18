package com.dormrepair.remindme.handler;

import com.dormrepair.remindme.exception.AccessForbiddenException;
import com.dormrepair.remindme.exception.ExternalServiceException;
import com.dormrepair.remindme.exception.FileParseException;
import com.dormrepair.remindme.exception.InvalidInputException;
import com.dormrepair.remindme.exception.ResourceNotFoundException;
import com.dormrepair.remindme.utils.TimeUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

// 全局异常处理器。@RestControllerAdvice 拦截所有 Controller 抛出的异常，统一转换为 {success, message, timestamp, errors?} JSON 响应，
// 避免 Spring 默认的 Whitelabel 错误页直接暴露 500/堆栈信息
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 捕获业务参数非法场景。如手机号已存在、文件超大小、验证码错误等主动 throw IllegalArgumentException 的情况，
    // 日志打 warn（不是 error），HTTP 状态返回 400 BAD_REQUEST
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("业务异常: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    // 捕获 Spring Validation 参数校验失败（@Valid 注解触发）。
    // 遍历 BindingResult，收集每个字段的错误信息到 errors Map，字段名→错误消息；
    // 调用方可据此按字段回填错误文案；日志打 warn；HTTP 400
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(MethodArgumentNotValidException e) {
        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        log.warn("参数校验失败: {}", errors);

        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", "参数校验失败");
        response.put("errors", errors);
        response.put("timestamp", TimeUtils.now());

        return ResponseEntity.badRequest().body(response);
    }

    // 兜底全局异常捕获。所有未被上面两个 Handler 捕获的异常（空指针、数组越界、I/O 异常、第三方库异常等）全部走这里；
    // 日志打 error 带完整堆栈，返回给调用方的 message 模糊成「系统内部错误」，避免把 SQL/路径等敏感信息暴露出去；HTTP 500
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception e) {
        log.error("系统异常: ", e);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "系统内部错误");
    }

    // ==================== 阶段 D：补充外部依赖与请求参数类异常处理器 ====================

    // 捕获 RestTemplate 调用外部 API 抛出的 RestClientException（含 SocketTimeoutException 包装、5xx 等）。
    // 服务端暂时无法连接外部依赖，返回 503 SERVICE_UNAVAILABLE，提示用户稍后重试；日志 error
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, Object>> handleRestClientException(RestClientException e) {
        log.error("外部服务调用失败: ", e);
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, "外部服务暂不可用，请稍后重试");
    }

    // 捕获 Jackson JSON 序列化/反序列化异常（如 LLM 返回非法 JSON、用户请求体 JSON 格式错误等）。
    // 通常为客户端或上游输入问题，返回 400 BAD_REQUEST；日志 warn
    @ExceptionHandler(JsonProcessingException.class)
    public ResponseEntity<Map<String, Object>> handleJsonProcessingException(JsonProcessingException e) {
        log.warn("JSON 处理失败: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "数据格式错误，请检查输入内容");
    }

    // 捕获 Spring DataAccessException（含所有子类如 DataIntegrityViolationException、DuplicateKeyException 等）。
    // 数据库操作失败可能是连接断开、约束冲突等，返回 500 INTERNAL_SERVER_ERROR；日志 error 带堆栈
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccessException(DataAccessException e) {
        log.error("数据库操作失败: ", e);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "数据库操作失败，请稍后重试");
    }

    // 捕获 Spring Security 权限不足异常（用户已登录但无权操作该资源）。
    // 返回 403 FORBIDDEN；日志 warn（非系统错误，属业务级访问控制）
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDeniedException(AccessDeniedException e) {
        log.warn("权限不足: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, "权限不足，无法操作该资源");
    }

    // 捕获缺少必填参数异常（Controller 方法标注 @RequestParam 但请求未提供）。
    // 从异常对象提取参数名拼到 message，返回 400 BAD_REQUEST；日志 warn
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        String paramName = e.getParameterName();
        log.warn("缺少必填参数: {}", paramName);
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "缺少必填参数：" + paramName);
    }

    // 捕获请求体不可读异常（JSON 格式错误、字段类型不匹配等导致 @RequestBody 反序列化失败）。
    // 返回 400 BAD_REQUEST；日志 warn
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("请求体格式错误: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "请求体格式错误，请检查 JSON 格式");
    }

    // 捕获参数类型不匹配异常（如路径变量期望 Long 但传了字符串）。
    // 从异常对象提取参数名和期望类型拼到 message，返回 400 BAD_REQUEST；日志 warn
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        String paramName = e.getName();
        // getRequiredType() 在无法推断类型时返回 null，需做空安全处理
        String expectedType = e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "未知";
        log.warn("参数类型错误: {} 应为 {}", paramName, expectedType);
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "参数类型错误：" + paramName + " 应为 " + expectedType);
    }

    // ==================== 阶段 E：自定义业务异常处理器 ====================

    // 捕获资源不存在异常（提醒/用户/会话不存在等）。返回 404 NOT_FOUND；日志 warn（业务级，非系统错误）
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleResourceNotFoundException(ResourceNotFoundException e) {
        log.warn("资源不存在: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
    }

    // 捕获权限不足异常（用户已登录但无权操作该资源）。返回 403 FORBIDDEN；日志 warn
    @ExceptionHandler(AccessForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleAccessForbiddenException(AccessForbiddenException e) {
        log.warn("权限不足: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, e.getMessage());
    }

    // 捕获外部服务故障异常（和风天气、通义千问、ChromaDB 等外部 API 调用失败）。
    // 返回 503 SERVICE_UNAVAILABLE；日志 error 带堆栈（便于排查外部依赖问题）
    @ExceptionHandler(ExternalServiceException.class)
    public ResponseEntity<Map<String, Object>> handleExternalServiceException(ExternalServiceException e) {
        log.error("外部服务故障: ", e);
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    // 捕获用户输入错误异常（参数格式错误、值非法、缺少必填项等）。返回 400 BAD_REQUEST；日志 warn
    @ExceptionHandler(InvalidInputException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidInputException(InvalidInputException e) {
        log.warn("输入非法: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    // 捕获文件解析异常及其子类（Encrypted/Corrupted/UnsupportedFormat）。
    // @ExceptionHandler 会自动匹配子类，故一个 Handler 即可覆盖所有文件解析场景。返回 400 BAD_REQUEST；日志 warn
    @ExceptionHandler(FileParseException.class)
    public ResponseEntity<Map<String, Object>> handleFileParseException(FileParseException e) {
        log.warn("文件解析失败: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    // 通用错误响应构造方法。统一填充 success=false + message + timestamp；
    // HTTP 状态码由调用方（上面所有 Handler）根据异常类型传入
    private ResponseEntity<Map<String, Object>> buildErrorResponse(HttpStatus status, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        response.put("timestamp", TimeUtils.now());

        return ResponseEntity.status(status).body(response);
    }
}
