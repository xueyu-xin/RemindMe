package com.dormrepair.remindme.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// 登录成功响应 DTO。对应 /api/auth/login 接口的返回体，承载用户基础信息
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    // 用户 id，登录后回传给后端用于用户维度的接口调用
    private Long userId;
    // 用户昵称，可能为空（未设置时）
    private String nickname;
    // 头像 URL（相对 uploads 根目录，访问时拼上 /uploads 前缀）
    private String avatar;
}
