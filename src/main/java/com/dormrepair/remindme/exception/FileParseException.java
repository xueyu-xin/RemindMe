package com.dormrepair.remindme.exception;

/**
 * 文件解析异常，映射 HTTP 400。
 * 包含三种具体子类型：加密、损坏、格式不支持。
 */
public class FileParseException extends BusinessException {

    public FileParseException(String message) {
        super(message);
    }

    public FileParseException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 文件已加密，需去除密码后重传 */
    public static class Encrypted extends FileParseException {
        public Encrypted(String message) {
            super(message);
        }
    }

    /** 文件已损坏，请检查原文件 */
    public static class Corrupted extends FileParseException {
        public Corrupted(String message) {
            super(message);
        }
    }

    /** 文件格式不支持 */
    public static class UnsupportedFormat extends FileParseException {
        public UnsupportedFormat(String message) {
            super(message);
        }
    }
}
