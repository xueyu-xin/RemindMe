package com.dormrepair.remindme.exception;

/**
 * 外部服务故障异常，映射 HTTP 503。
 * 用于外部 API（如和风天气、通义千问、ChromaDB）调用失败的场景。
 */
public class ExternalServiceException extends BusinessException {

    public ExternalServiceException(String message) {
        super(message);
    }

    public ExternalServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
