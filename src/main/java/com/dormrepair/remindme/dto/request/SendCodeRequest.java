package com.dormrepair.remindme.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

// 发送短信验证码请求 DTO。对应 POST /api/auth/send-code 接口，仅承载接收验证码的目标手机号
@Data
public class SendCodeRequest {

    // 目标手机号，必须以 1 开头且第二位为 3~9 共 11 位，用于注册/登录前下发验证码
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;
}
