package com.dormrepair.remindme.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

// 注册请求 DTO。对应 /api/auth/register 接口的请求体，承载手机号、短信验证码、密码三项注册信息，通过注解完成入参格式校验
@Data
public class RegisterRequest {

    // 用户注册手机号，要求非空且严格 11 位，注册成功后作为登录账号
    @NotBlank(message = "手机号不能为空")
    @Size(min = 11, max = 11, message = "手机号必须为11位")
    private String phone;

    // 短信验证码，要求非空且严格 6 位，由 /send-code 接口下发后填入
    @NotBlank(message = "验证码不能为空")
    @Size(min = 6, max = 6, message = "验证码必须为6位")
    private String code;

    // 登录密码，要求非空且长度 8~50 位，落库前会经 BCrypt 加密后再存
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 50, message = "密码长度必须为8-50位")
    private String password;
}
