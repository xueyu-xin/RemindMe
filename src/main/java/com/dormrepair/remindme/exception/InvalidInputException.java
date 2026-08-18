package com.dormrepair.remindme.exception;

/**
 * 用户输入错误异常，映射 HTTP 400。
 * 用于参数格式错误、值非法、缺少必填项等场景。
 */
public class InvalidInputException extends BusinessException {

    public InvalidInputException(String message) {
        super(message);
    }

    public InvalidInputException(String message, Throwable cause) {
        super(message, cause);
    }
}
