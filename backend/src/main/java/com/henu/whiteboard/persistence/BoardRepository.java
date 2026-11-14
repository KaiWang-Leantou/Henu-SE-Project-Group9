package com.henu.whiteboard.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * wb_board 的 JPA 仓储；Specification 支持作品库的动态筛选与分页。
 */
public interface BoardRepository extends JpaRepository<BoardEntity, String>,
        JpaSpecificationExecutor<BoardEntity> {
}
