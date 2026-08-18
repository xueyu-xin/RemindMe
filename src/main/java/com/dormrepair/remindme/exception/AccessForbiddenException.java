package com.dormrepair.remindme.exception;

/**
 * 权限不足异常，映射 HTTP 403。
 * 用于用户已登录但无权操作该资源的场景。
 */
public class AccessForbiddenException extends BusinessException {

    public AccessForbiddenException(String message) {
        super(message);
    }

    public AccessForbiddenException(String message, Throwable cause) {
        super(message, cause);
    }
}
