package com.henu.whiteboard.board;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.henu.whiteboard.auth.CurrentUser;
import com.henu.whiteboard.common.ApiCode;
import com.henu.whiteboard.common.ApiException;
import com.henu.whiteboard.common.TimeFormat;
import com.henu.whiteboard.persistence.BoardEntity;
import com.henu.whiteboard.persistence.BoardOpEntity;
import com.henu.whiteboard.persistence.BoardOpRepository;
import com.henu.whiteboard.persistence.BoardRepository;
import com.henu.whiteboard.persistence.BoardVersionEntity;
import com.henu.whiteboard.persistence.BoardVersionKind;
import com.henu.whiteboard.persistence.BoardVersionRepository;
import com.henu.whiteboard.persistence.UserEntity;
import com.henu.whiteboard.persistence.UserRepository;
import com.henu.whiteboard.room.Room;
import com.henu.whiteboard.room.RoomRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 版本历史服务：手动/自动/回滚前快照、时间轴查询、五步回滚与每日 GC。
 *
 * <p>version_no 的“读最大值再 +1”用单板 JVM 锁 + TransactionTemplate 包住，
 * 保证提交完成后才释放锁；Docker 单实例部署下即可避免并发重号。</p>
 */
@Service
public class BoardVersionService {

    private static final Logger log = LoggerFactory.getLogger(BoardVersionService.class);

    /** MANUAL 永久保留，但单板最多 100 个（docs/07 §1.2）。 */
    private static final int MAX_MANUAL_VERSIONS = 100;
    /** 单板版本总数上限，超过后先删最旧 AUTO、再删最旧 PRE_RESTORE。 */
    private static final int MAX_VERSIONS_PER_BOARD = 200;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int AUTO_RETAIN_RECENT = 30;
    private static final long AUTO_OP_THRESHOLD = 200L;
    private static final long AUTO_INTERVAL_MINUTES = 5L;
    private static final int RETENTION_DAYS = 30;
    private static final long RESTORE_TIMEOUT_SECONDS = 10L;
    /** 与 TimeFormat 保持一致，判断“每天首个 AUTO”用东八区自然日。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final BoardVersionRepository versionRepository;
    private final BoardRepository boardRepository;
    private final BoardOpRepository boardOpRepository;
    private final UserRepository userRepository;
    private final BoardAccessService boardAccessService;
    private final RoomRegistry roomRegistry;
    private final BoardEvents boardEvents;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    /** 单板锁：版本号分配与 GC 都在锁内完成。 */
    private final ConcurrentHashMap<String, Object> boardLocks = new ConcurrentHashMap<>();

    public BoardVersionService(BoardVersionRepository versionRepository,
                               BoardRepository boardRepository,
                               BoardOpRepository boardOpRepository,
                               UserRepository userRepository,
                               BoardAccessService boardAccessService,
                               RoomRegistry roomRegistry,
                               BoardEvents boardEvents,
                               ObjectMapper objectMapper,
                               PlatformTransactionManager transactionManager) {
        this.versionRepository = versionRepository;
        this.boardRepository = boardRepository;
        this.boardOpRepository = boardOpRepository;
        this.userRepository = userRepository;
        this.boardAccessService = boardAccessService;
        this.roomRegistry = roomRegistry;
        this.boardEvents = boardEvents;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------
    // 生成
    // ------------------------------------------------------------------

    /** 手动存档：EDITOR+，游客不可（docs/04 §2）。快照取当前实时文档，避免丢最后 2 秒的笔划。 */
    public BoardVersionDtos.VersionView createManual(CurrentUser user, String boardId,
                                                    BoardVersionDtos.CreateVersionRequest request) {
        requireSignedIn(user, "游客不能存档版本，请先登录");
        BoardEntity board = boardAccessService.requireBoard(boardId);
        boardAccessService.requireEditor(board, user.id());
        boardAccessService.requireWritable(board);

        String label = normalizeLabel(request == null ? null : request.label());
        String thumbnail = request == null || request.thumbnail() == null
                ? null : BoardMetaService.validateThumbnail(request.thumbnail());
        DocSnapshot snapshot = liveSnapshot(board);

        BoardVersionEntity created = inBoardTransaction(boardId, () -> {
            long manualCount = versionRepository.countByBoardIdAndKind(boardId, BoardVersionKind.MANUAL);
            if (manualCount >= MAX_MANUAL_VERSIONS) {
                throw new ApiException(ApiCode.VALIDATION_ERROR,
                        "单板手动版本已达上限 " + MAX_MANUAL_VERSIONS + " 个");
            }
            return insertVersion(boardId, BoardVersionKind.MANUAL, label, snapshot.json(),
                    snapshot.seq(), thumbnail, user.id(), Instant.now());
        });
        return toView(created, Map.of(user.id(), user.nickname()));
    }

    /**
     * AUTO 自动版本：由 BoardService 在快照落库后调用（运行在快照线程，不阻塞请求线程）。
     * 触发条件：距上一个 AUTO ≥5 分钟，或自上个 AUTO 累计 ≥200 op；首个 AUTO 直接生成。
     */
    public void maybeCreateAutoVersion(String boardId, String docJson, long docSeq, Instant savedAt) {
        if (boardId == null || docJson == null || docJson.isBlank()) {
            return;
        }
        try {
            Boolean created = inBoardTransaction(boardId, () -> {
                BoardVersionEntity last = versionRepository
                        .findFirstByBoardIdAndKindOrderByVersionNoDesc(boardId, BoardVersionKind.AUTO)
                        .orElse(null);
                if (last != null && docSeq <= last.getDocSeq()) {
                    return false;
                }
                boolean due;
                if (last == null || last.getCreatedAt() == null) {
                    due = true;
                } else {
                    long opDelta = docSeq - last.getDocSeq();
                    long minutes = Duration.between(last.getCreatedAt(), savedAt).toMinutes();
                    due = opDelta >= AUTO_OP_THRESHOLD || minutes >= AUTO_INTERVAL_MINUTES;
                }
                if (!due) {
                    return false;
                }
                insertVersion(boardId, BoardVersionKind.AUTO, null, docJson, docSeq,
                        null, null, savedAt);
                return true;
            });
            if (Boolean.TRUE.equals(created)) {
                log.debug("生成 AUTO 版本 board={} seq={}", boardId, docSeq);
            }
        } catch (RuntimeException e) {
            // 自动版本失败不能影响快照主流程
            log.warn("生成 AUTO 版本失败 board={} seq={}：{}", boardId, docSeq, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public BoardVersionDtos.VersionListData list(CurrentUser user, String boardId, int page, int size) {
        BoardEntity board = boardAccessService.requireBoard(boardId);
        boardAccessService.requireReadRole(board, user.id());
        if (page < 1) {
            throw new ApiException(ApiCode.VALIDATION_ERROR, "page 必须从 1 开始");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(ApiCode.VALIDATION_ERROR, "size 范围需为 1–" + MAX_PAGE_SIZE);
        }
        Page<BoardVersionEntity> result = versionRepository
                .findByBoardIdOrderByVersionNoDesc(boardId, PageRequest.of(page - 1, size));
        Map<String, String> nicknames = nicknamesOf(result.getContent());
        List<BoardVersionDtos.VersionView> items = result.getContent().stream()
                .map(row -> toView(row, nicknames))
                .toList();
        return new BoardVersionDtos.VersionListData(result.getTotalElements(), page, size, items);
    }

    // ------------------------------------------------------------------
    // 回滚（docs/07 §1.4 五步，不可简化）
    // ------------------------------------------------------------------

    /**
     * 回滚到指定版本。房间存在时把整个回滚丢进房间串行执行器，避免与正在画的 op 交叉；
     * 否则直接在请求线程执行。返回前已完成 ①–④ 落库与 ⑤ init 广播。
     */
    public BoardVersionDtos.RestoreResult restore(CurrentUser user, String boardId, String versionId) {
        requireSignedIn(user, "游客不能回滚版本，请先登录");
        BoardEntity board = boardAccessService.requireBoard(boardId);
        boardAccessService.requireOwner(board, user.id());
        boardAccessService.requireWritable(board);
        // 尽早 404，避免把明显不存在的 versionId 丢进房间执行器
        versionRepository.findByIdAndBoardId(versionId, boardId)
                .orElseThrow(() -> new ApiException(ApiCode.NOT_FOUND, "版本不存在"));

        Room room = roomRegistry.get(boardId);
        if (room == null || room.isClosed()) {
            return doRestore(boardId, versionId, user.id());
        }
        try {
            Future<BoardVersionDtos.RestoreResult> future = room.executor()
                    .submit(() -> doRestore(boardId, versionId, user.id()));
            return future.get(RESTORE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            log.warn("房间执行器已关闭，回滚改在请求线程执行 board={}", boardId);
            return doRestore(boardId, versionId, user.id());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ApiCode.INTERNAL, "回滚被中断，请重试");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ApiException apiException) {
                throw apiException;
            }
            log.error("回滚失败 board={} version={}", boardId, versionId, cause);
            throw new ApiException(ApiCode.INTERNAL, "回滚失败，请稍后重试");
        } catch (TimeoutException e) {
            log.warn("回滚等待房间串行执行器超时 board={}", boardId);
            throw new ApiException(ApiCode.INTERNAL, "回滚超时，请稍后重试");
        }
    }

    private BoardVersionDtos.RestoreResult doRestore(String boardId, String versionId, String operatorId) {
        RestoreOutcome outcome = inBoardTransaction(boardId, () -> {
            BoardEntity board = boardRepository.findById(boardId)
                    .orElseThrow(() -> new ApiException(ApiCode.NOT_FOUND, "白板不存在"));
            BoardVersionEntity target = versionRepository.findByIdAndBoardId(versionId, boardId)
                    .orElseThrow(() -> new ApiException(ApiCode.NOT_FOUND, "版本不存在"));
            Instant now = Instant.now();

            // ① 当前 doc 存为 PRE_RESTORE：房间存在时取实时文档（可能比快照新 2 秒）
            DocSnapshot current = liveSnapshot(board);
            insertVersion(boardId, BoardVersionKind.PRE_RESTORE, null, current.json(),
                    current.seq(), board.getThumbnail(), operatorId, now);

            // ② 目标 doc 覆盖当前 doc，seq = max(当前 seq, 目标 docSeq) + 1
            BoardDoc restored = BoardDoc.fromJson(target.getDocJson(), boardId);
            long newSeq = Math.max(current.seq(), target.getDocSeq()) + 1L;
            restored.setSeq(newSeq);
            String restoredJson = restored.toJson();

            // ③ 落一条可审计、可重放的 board.restore op
            ObjectNode cmd = objectMapper.createObjectNode();
            cmd.put("t", "board.restore");
            cmd.put("versionId", target.getId());
            cmd.put("versionNo", target.getVersionNo());
            BoardOpEntity op = new BoardOpEntity();
            op.setBoardId(boardId);
            op.setSeq(newSeq);
            op.setActorId(operatorId);
            op.setCh("board");
            op.setCmdJson(cmd.toString());
            op.setCreatedAt(now);
            boardOpRepository.save(op);

            // ④ 更新白板快照字段
            board.setDocJson(restoredJson);
            board.setDocSeq(newSeq);
            board.setLastSavedAt(now);
            boardRepository.save(board);

            return new RestoreOutcome(restoredJson, newSeq, target.getId(),
                    target.getVersionNo(), cmd);
        });

        // ⑤ 房间存在时：替换权威文档、推进 seq，广播 board.reload + init（全量重同步）
        Room room = roomRegistry.get(boardId);
        if (room != null && !room.isClosed()) {
            room.adopt(outcome.docJson(), outcome.seq());
            room.recordOp(new OpRecord(outcome.seq(), operatorId, "board", outcome.opCmd()));
        }
        boardEvents.reloadAndInit(boardId, "restore");
        return new BoardVersionDtos.RestoreResult(outcome.versionId(), outcome.versionNo(),
                outcome.seq());
    }

    // ------------------------------------------------------------------
    // GC（每日 03:00）
    // ------------------------------------------------------------------

    /**
     * 保留策略（docs/07 §1.2）：
     * AUTO 只留最近 30 个 + 30 天内每天首个；PRE_RESTORE 只留 30 天；
     * 单板总数 >200 时按“最旧 AUTO → 最旧 PRE_RESTORE”删到 200；MANUAL 永不自动删。
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void gc() {
        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        List<String> boardIds;
        try {
            boardIds = versionRepository.findDistinctBoardIds();
        } catch (RuntimeException e) {
            log.warn("版本 GC 扫描失败：{}", e.getMessage());
            return;
        }
        int cleaned = 0;
        for (String boardId : boardIds) {
            try {
                Integer deleted = inBoardTransaction(boardId, () -> gcBoard(boardId, cutoff));
                cleaned += deleted == null ? 0 : deleted;
            } catch (RuntimeException e) {
                log.warn("版本 GC 跳过 board={}：{}", boardId, e.getMessage());
            }
        }
        if (cleaned > 0) {
            log.info("版本 GC 完成，清理 {} 条", cleaned);
        }
    }

    private int gcBoard(String boardId, Instant cutoff) {
        List<BoardVersionEntity> all = versionRepository.findByBoardIdOrderByVersionNoAsc(boardId);
        if (all.isEmpty()) {
            return 0;
        }
        List<BoardVersionEntity> autos = new ArrayList<>();
        for (BoardVersionEntity row : all) {
            if (BoardVersionKind.AUTO.equals(row.getKind())) {
                autos.add(row);
            }
        }

        // 最近 30 个 AUTO：按版本号（升序列表的末尾）保留
        Set<String> recentAutoIds = new HashSet<>();
        for (int i = Math.max(0, autos.size() - AUTO_RETAIN_RECENT); i < autos.size(); i++) {
            recentAutoIds.add(autos.get(i).getId());
        }
        // 每天首个 AUTO：30 天内即使不在最近 30 个也保留
        Set<String> firstOfDayIds = new HashSet<>();
        Set<LocalDate> seenDays = new HashSet<>();
        for (BoardVersionEntity row : autos) {
            LocalDate day = row.getCreatedAt().atZone(ZONE).toLocalDate();
            if (seenDays.add(day)) {
                firstOfDayIds.add(row.getId());
            }
        }

        Set<String> deleteIds = new HashSet<>();
        for (BoardVersionEntity row : autos) {
            if (recentAutoIds.contains(row.getId())) {
                continue;
            }
            boolean expired = row.getCreatedAt().isBefore(cutoff);
            boolean firstOfDay = firstOfDayIds.contains(row.getId());
            if (expired || !firstOfDay) {
                deleteIds.add(row.getId());
            }
        }
        for (BoardVersionEntity row : all) {
            if (BoardVersionKind.PRE_RESTORE.equals(row.getKind())
                    && row.getCreatedAt().isBefore(cutoff)) {
                deleteIds.add(row.getId());
            }
        }

        // 单板总数上限：先删最旧 AUTO，再删最旧 PRE_RESTORE；MANUAL 永不动
        long total = all.size() - deleteIds.size();
        if (total > MAX_VERSIONS_PER_BOARD) {
            for (BoardVersionEntity row : all) {
                if (total <= MAX_VERSIONS_PER_BOARD) {
                    break;
                }
                if (BoardVersionKind.AUTO.equals(row.getKind()) && deleteIds.add(row.getId())) {
                    total--;
                }
            }
            for (BoardVersionEntity row : all) {
                if (total <= MAX_VERSIONS_PER_BOARD) {
                    break;
                }
                if (BoardVersionKind.PRE_RESTORE.equals(row.getKind()) && deleteIds.add(row.getId())) {
                    total--;
                }
            }
        }
        if (deleteIds.isEmpty()) {
            return 0;
        }
        versionRepository.deleteAllById(deleteIds);
        log.info("版本 GC board={} 清理 {} 条，剩余 {} 条", boardId, deleteIds.size(), total);
        return deleteIds.size();
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 手动存档/回滚使用的当前实时快照：房间存在取房间文档，否则取库内快照。 */
    private DocSnapshot liveSnapshot(BoardEntity board) {
        Room room = roomRegistry.get(board.getId());
        if (room != null && !room.isClosed()) {
            BoardDoc.Snapshot snapshot = room.doc().snapshot();
            return new DocSnapshot(snapshot.json(), snapshot.seq());
        }
        return currentSnapshot(board);
    }

    private DocSnapshot currentSnapshot(BoardEntity board) {
        String json = board.getDocJson();
        long seq = board.getDocSeq();
        if (json == null || json.isBlank()) {
            BoardDoc empty = BoardDoc.create(board.getId());
            return new DocSnapshot(empty.toJson(), 0L);
        }
        return new DocSnapshot(json, seq);
    }

    private BoardVersionEntity insertVersion(String boardId, String kind, String label,
                                             String docJson, long docSeq, String thumbnail,
                                             String authorId, Instant createdAt) {
        long nextNo = versionRepository.findFirstByBoardIdOrderByVersionNoDesc(boardId)
                .map(row -> row.getVersionNo() + 1L)
                .orElse(1L);
        if (nextNo > Integer.MAX_VALUE) {
            throw new ApiException(ApiCode.INTERNAL, "版本号已耗尽");
        }
        BoardVersionEntity entity = new BoardVersionEntity();
        entity.setId("v_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        entity.setBoardId(boardId);
        entity.setVersionNo((int) nextNo);
        entity.setKind(kind);
        entity.setLabel(label);
        entity.setDocJson(docJson);
        entity.setDocSeq(docSeq);
        entity.setThumbnail(thumbnail);
        entity.setAuthorId(authorId);
        entity.setCreatedAt(createdAt == null ? Instant.now() : createdAt);
        return versionRepository.save(entity);
    }

    private BoardVersionDtos.VersionView toView(BoardVersionEntity entity,
                                               Map<String, String> nicknames) {
        String authorId = entity.getAuthorId();
        String author = authorId == null ? null : nicknames.getOrDefault(authorId, authorId);
        return new BoardVersionDtos.VersionView(
                entity.getId(),
                entity.getVersionNo(),
                entity.getKind(),
                entity.getLabel(),
                entity.getDocSeq(),
                author,
                TimeFormat.iso(entity.getCreatedAt()),
                entity.getThumbnail());
    }

    private Map<String, String> nicknamesOf(List<BoardVersionEntity> rows) {
        Set<String> authorIds = new HashSet<>();
        for (BoardVersionEntity row : rows) {
            if (row.getAuthorId() != null) {
                authorIds.add(row.getAuthorId());
            }
        }
        if (authorIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        for (UserEntity user : userRepository.findAllById(authorIds)) {
            result.put(user.getId(), user.getNickname());
        }
        return result;
    }

    /** 单板锁 + 事务：锁释放时事务已提交，version_no 不会重号。 */
    private <T> T inBoardTransaction(String boardId, Supplier<T> action) {
        Objects.requireNonNull(boardId, "boardId");
        Object lock = boardLocks.computeIfAbsent(boardId, key -> new Object());
        synchronized (lock) {
            return transactionTemplate.execute(status -> action.get());
        }
    }

    private static String normalizeLabel(String raw) {
        if (raw == null) {
            return null;
        }
        String label = raw.trim();
        if (label.isEmpty()) {
            return null;
        }
        if (label.length() > 100) {
            throw new ApiException(ApiCode.VALIDATION_ERROR, "label 最长 100 个字符");
        }
        return label;
    }

    private static void requireSignedIn(CurrentUser user, String message) {
        if (user == null || user.id() == null) {
            throw new ApiException(ApiCode.AUTH_REQUIRED, "请先登录");
        }
        if (user.guest()) {
            throw new ApiException(ApiCode.FORBIDDEN, message);
        }
    }

    private record DocSnapshot(String json, long seq) {
    }

    private record RestoreOutcome(String docJson, long seq, String versionId, int versionNo,
                                  ObjectNode opCmd) {
    }
}
