package com.henu.whiteboard.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * wb_board_version 的 JPA 仓储：时间轴分页、找上一个 AUTO、单板 GC 查询。
 */
public interface BoardVersionRepository extends JpaRepository<BoardVersionEntity, String> {

    /** 版本抽屉：按版本号倒序分页。 */
    Page<BoardVersionEntity> findByBoardIdOrderByVersionNoDesc(String boardId, Pageable pageable);

    /** 单板当前最大版本号（用于 version_no 自增）。 */
    Optional<BoardVersionEntity> findFirstByBoardIdOrderByVersionNoDesc(String boardId);

    /** 最近一个指定类型的版本，AUTO 触发与保留策略用。 */
    Optional<BoardVersionEntity> findFirstByBoardIdAndKindOrderByVersionNoDesc(String boardId, String kind);

    long countByBoardIdAndKind(String boardId, String kind);

    long countByBoardId(String boardId);

    Optional<BoardVersionEntity> findByIdAndBoardId(String id, String boardId);

    /** GC 扫描：所有存在版本的板。 */
    @Query("select distinct v.boardId from BoardVersionEntity v")
    List<String> findDistinctBoardIds();

    /** 单板全量版本（升序），GC 计算保留集用；课程项目数据量小，可接受。 */
    List<BoardVersionEntity> findByBoardIdOrderByVersionNoAsc(String boardId);
}
