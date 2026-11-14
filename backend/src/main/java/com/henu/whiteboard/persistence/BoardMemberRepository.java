package com.henu.whiteboard.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * wb_board_member 的 JPA 仓储，供角色判定与作品库筛选使用。
 */
public interface BoardMemberRepository extends JpaRepository<BoardMemberEntity, BoardMemberEntity.MemberId> {

    List<BoardMemberEntity> findByBoardIdOrderByJoinedAtAsc(String boardId);

    List<BoardMemberEntity> findByUserId(String userId);

    Optional<BoardMemberEntity> findByBoardIdAndUserId(String boardId, String userId);
}
