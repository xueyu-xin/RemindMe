package com.dormrepair.remindme.exception;

/**
 * 资源不存在异常，映射 HTTP 404。
 * 用于提醒不存在、用户不存在、会话不存在等场景。
 */
public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
