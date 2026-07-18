package com.dormrepair.remindme.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录请求DTO
 */
@Data
public class LoginRequest {

    @NotBlank(message = "手机号不能为空")
    @Size(min = 11, max = 11, message = "手机号必须为11位")
    private String phone;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 50, message = "密码长度必须为8-50位")
    private String password;
}
