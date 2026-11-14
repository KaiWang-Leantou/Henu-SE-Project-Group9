package com.henu.whiteboard.board;

import com.henu.whiteboard.persistence.BoardVersionEntity;
import com.henu.whiteboard.persistence.BoardVersionKind;
import com.henu.whiteboard.persistence.BoardVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 版本 GC 策略测试：AUTO 保留最近 30 + 每天首个、PRE_RESTORE 30 天、
 * MANUAL 永不自动删、单板上限 200 时先删最旧 AUTO/PRE_RESTORE。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class VersionGcTest {

    private static final String EMPTY_DOC =
            "{\"schemaVersion\":1,\"pages\":[{\"id\":\"p_1\",\"name\":\"第 1 页\",\"roundId\":null,\"elements\":[]}]}";

    @Autowired
    private BoardVersionService boardVersionService;

    @Autowired
    private BoardVersionRepository boardVersionRepository;

    @Autowired
    private BoardAccessService boardAccessService;

    @Test
    void retentionKeepsRecentAutoAndDailyFirstButDropsOldPreRestore() {
        String boardId = boardAccessService
                .createBoard("u_gc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                        "GC 保留策略")
                .getId();
        Instant now = Instant.now();
        // 40 个同一天的 AUTO：最近 30 个 + 每天首个 = 31，最旧 9 个删除
        for (int i = 1; i <= 40; i++) {
            insert(boardId, i, BoardVersionKind.AUTO, now);
        }
        // 3 个 40 天前的 PRE_RESTORE：全部过期删除
        for (int i = 41; i <= 43; i++) {
            insert(boardId, i, BoardVersionKind.PRE_RESTORE, now.minus(40, ChronoUnit.DAYS));
        }
        // 2 个 40 天前的 MANUAL：永不自动删除
        insert(boardId, 44, BoardVersionKind.MANUAL, now.minus(40, ChronoUnit.DAYS));
        insert(boardId, 45, BoardVersionKind.MANUAL, now.minus(40, ChronoUnit.DAYS));

        boardVersionService.gc();

        assertEquals(31, boardVersionRepository.countByBoardIdAndKind(boardId, BoardVersionKind.AUTO),
                "AUTO 应保留最近 30 个 + 每天首个");
        assertEquals(0, boardVersionRepository
                        .countByBoardIdAndKind(boardId, BoardVersionKind.PRE_RESTORE),
                "30 天前的 PRE_RESTORE 应被清理");
        assertEquals(2, boardVersionRepository
                        .countByBoardIdAndKind(boardId, BoardVersionKind.MANUAL),
                "MANUAL 永不自动删除");
    }

    @Test
    void totalOver200DropsOldestPreRestoreButKeepsManual() {
        String boardId = boardAccessService
                .createBoard("u_gc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                        "GC 上限")
                .getId();
        Instant now = Instant.now();
        // 250 个仍在保留期内（30 天内）的 PRE_RESTORE + 1 个 MANUAL → 超出 200 上限
        for (int i = 1; i <= 250; i++) {
            insert(boardId, i, BoardVersionKind.PRE_RESTORE, now.minusSeconds(250L - i));
        }
        insert(boardId, 251, BoardVersionKind.MANUAL, now);

        boardVersionService.gc();

        assertEquals(200, boardVersionRepository.countByBoardId(boardId),
                "单板版本总数应被压到 200");
        assertEquals(199, boardVersionRepository
                        .countByBoardIdAndKind(boardId, BoardVersionKind.PRE_RESTORE),
                "先按最旧 PRE_RESTORE 清理");
        assertEquals(1, boardVersionRepository
                        .countByBoardIdAndKind(boardId, BoardVersionKind.MANUAL),
                "MANUAL 不能被上限清理");
    }

    private void insert(String boardId, int versionNo, String kind, Instant createdAt) {
        BoardVersionEntity entity = new BoardVersionEntity();
        entity.setId("v_gc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        entity.setBoardId(boardId);
        entity.setVersionNo(versionNo);
        entity.setKind(kind);
        entity.setDocJson(EMPTY_DOC);
        entity.setDocSeq(versionNo);
        entity.setCreatedAt(createdAt);
        boardVersionRepository.save(entity);
    }
}
