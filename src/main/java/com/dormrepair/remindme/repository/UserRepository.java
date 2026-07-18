package com.dormrepair.remindme.repository;

import com.dormrepair.remindme.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 用户 Repository
 * <p>
 * 对应实体：{@link User}
 * 对应表名：`users`
 * </p>
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByPhone(String phone);

    boolean existsByPhone(String phone);
}
