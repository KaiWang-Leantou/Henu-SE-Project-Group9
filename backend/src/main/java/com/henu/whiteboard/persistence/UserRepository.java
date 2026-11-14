package com.henu.whiteboard.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * wb_user 的 JPA 仓储；用户名唯一约束是注册/升级并发的最后一道防线。
 */
public interface UserRepository extends JpaRepository<UserEntity, String> {

    Optional<UserEntity> findByUsername(String username);

    boolean existsByUsername(String username);
}
