package com.dormrepair.remindme.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// 统一响应结果封装。Controller 层所有接口的通用返回体，通过 code + message + data 三段式结构保持前后端接口契约一致
// T - 业务数据的泛型类型，支持任意 DTO/List/Map 等对象
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    // HTTP 业务状态码：约定 200 表示成功，500 表示业务失败；不与标准 HTTP 状态码强绑定
    private int code;

    // 提示文案：成功时通常为 "success"，失败时返回可直接展示给用户的错误描述
    private String message;

    // 业务数据：成功时承载响应 DTO，失败时为 null
    private T data;

    // 快速构造成功响应（默认 success 文案 + 指定数据）
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "success", data);
    }

    // 快速构造成功响应（自定义成功文案 + 指定数据）
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(200, message, data);
    }

    // 快速构造失败响应（固定 code=500，自定义错误文案，data=null）
    public static <T> Result<T> error(String message) {
        return new Result<>(500, message, null);
    }
}
