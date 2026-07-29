package com.dormrepair.remindme.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

// 登录请求 DTO。对应 /api/auth/login 接口的请求体，承载手机号和密码两项登录凭证
@Data
public class LoginRequest {

    // 注册时填写的手机号，登录时与密码配对校验，要求非空且严格 11 位
    @NotBlank(message = "手机号不能为空")
    @Size(min = 11, max = 11, message = "手机号必须为11位")
    private String phone;

    // 用户明文密码，经 BCrypt 与数据库存储的密文比对校验，要求非空且长度 8~50 位
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 50, message = "密码长度必须为8-50位")
    private String password;
}
