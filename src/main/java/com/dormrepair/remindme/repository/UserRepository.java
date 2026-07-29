package com.dormrepair.remindme.repository;

import com.dormrepair.remindme.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

// 用户核心数据访问接口。操作 users 表，两个高频派生查询：按手机号登录查重/取单、按手机号判断存在性（注册查重）
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // 登录/找回密码：按手机号取用户，查不到返回 Optional.empty() 让上层报 401
    Optional<User> findByPhone(String phone);

    // 注册前置校验：手机号是否已被占用；true=已存在不能再注册
    boolean existsByPhone(String phone);
}
