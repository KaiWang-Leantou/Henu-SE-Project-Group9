package com.henu.whiteboard.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * wb_board_op 的 JPA 仓储，提供按 seq 增量读取以支持恢复与重连。
 */
public interface BoardOpRepository extends JpaRepository<BoardOpEntity, BoardOpEntity.OpId> {

    List<BoardOpEntity> findByBoardIdAndSeqGreaterThanOrderBySeqAsc(String boardId, Long seq);
}
