package com.dormrepair.remindme.exception;

/**
 * 业务异常基类，继承 RuntimeException（非受检异常）。
 * 所有自定义业务异常应继承此类，便于 GlobalExceptionHandler 统一捕获。
 */
public abstract class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
