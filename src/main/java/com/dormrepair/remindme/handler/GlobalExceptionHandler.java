package com.dormrepair.remindme.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
        response.put("timestamp", LocalDateTime.now());

        return ResponseEntity.badRequest().body(response);
    }

    // 兜底全局异常捕获。所有未被上面两个 Handler 捕获的异常（空指针、数组越界、I/O 异常、第三方库异常等）全部走这里；
    // 日志打 error 带完整堆栈，返回给调用方的 message 模糊成「系统内部错误」，避免把 SQL/路径等敏感信息暴露出去；HTTP 500
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception e) {
        log.error("系统异常: ", e);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "系统内部错误");
    }

    // 通用错误响应构造方法。统一填充 success=false + message + timestamp；
    // HTTP 状态码由调用方（上面三个 Handler）根据异常类型传入
    private ResponseEntity<Map<String, Object>> buildErrorResponse(HttpStatus status, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        response.put("timestamp", LocalDateTime.now());

        return ResponseEntity.status(status).body(response);
    }
}
