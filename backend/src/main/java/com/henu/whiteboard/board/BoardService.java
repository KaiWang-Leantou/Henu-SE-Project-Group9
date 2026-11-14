package com.henu.whiteboard.board;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.henu.whiteboard.persistence.BoardEntity;
import com.henu.whiteboard.persistence.BoardMemberEntity;
import com.henu.whiteboard.persistence.BoardMemberRepository;
import com.henu.whiteboard.persistence.BoardOpEntity;
import com.henu.whiteboard.persistence.BoardOpRepository;
import com.henu.whiteboard.persistence.BoardRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * 白板文档的加载、op 异步落库与防抖快照服务。
 */
@Service
public class BoardService {

    private static final Logger log = LoggerFactory.getLogger(BoardService.class);
    private static final String SYSTEM_OWNER = "u_system";
    private static final int SNAPSHOT_OP_THRESHOLD = 50;
    private static final long SNAPSHOT_DEBOUNCE_SECONDS = 2L;

    private final BoardRepository boardRepository;
    private final BoardOpRepository boardOpRepository;
    private final BoardMemberRepository boardMemberRepository;
    private final BoardVersionService boardVersionService;
    private final ObjectMapper objectMapper;
    private final ExecutorService opWriter;
    private final ScheduledExecutorService snapshotScheduler;
    private final ConcurrentHashMap<String, SnapshotSlot> snapshotSlots = new ConcurrentHashMap<>();

    public BoardService(BoardRepository boardRepository,
                        BoardOpRepository boardOpRepository,
                        BoardMemberRepository boardMemberRepository,
                        BoardVersionService boardVersionService,
                        ObjectMapper objectMapper) {
        this.boardRepository = boardRepository;
        this.boardOpRepository = boardOpRepository;
        this.boardMemberRepository = boardMemberRepository;
        this.boardVersionService = boardVersionService;
        this.objectMapper = objectMapper;
        this.opWriter = Executors.newSingleThreadExecutor(daemonFactory("wb-op-writer"));
        this.snapshotScheduler = Executors.newSingleThreadScheduledExecutor(
                daemonFactory("wb-snapshot"));
    }

    /**
     * 兼容旧调用的加载入口：无主建板时归 u_system。
     */
    @Transactional
    public BoardDoc load(String boardId) {
        return loadOrCreate(boardId, SYSTEM_OWNER);
    }

    /**
     * 加载文档：优先读快照，再按 seq 补放 op 日志。
     * board 不存在时按 ownerId 自动创建空文档并补写 owner 成员行；
     * ownerId 为空时回退 u_system，保持旧行为。
     */
    @Transactional
    public BoardDoc loadOrCreate(String boardId, String ownerId) {
        requireBoardId(boardId);
        String effectiveOwner = ownerId == null || ownerId.isBlank() ? SYSTEM_OWNER : ownerId;
        BoardEntity board = boardRepository.findById(boardId).orElse(null);
        if (board == null) {
            BoardDoc doc = BoardDoc.create(boardId);
            board = new BoardEntity();
            board.setId(boardId);
            board.setName("白板 " + boardId);
            board.setOwnerId(effectiveOwner);
            board.setDocJson(doc.toJson());
            board.setDocSeq(0L);
            board.setPublic(true);
            board.setStatus("DRAFT");
            Instant now = Instant.now();
            board.setCreatedAt(now);
            board.setUpdatedAt(now);
            boardRepository.save(board);
            ensureOwnerMember(boardId, effectiveOwner);
            return doc;
        }

        // 旧数据缺成员行时补写，保证角色判定与作品库“我参与的”可用。
        if (board.getOwnerId() != null && board.getOwnerId().equals(ownerId)) {
            ensureOwnerMember(boardId, ownerId);
        }

        BoardDoc doc;
        long snapshotSeq = board.getDocSeq();
        if (board.getDocJson() == null || board.getDocJson().isBlank()) {
            doc = BoardDoc.create(boardId);
            board.setDocJson(doc.toJson());
            board.setDocSeq(0L);
            snapshotSeq = 0L;
            boardRepository.save(board);
        } else {
            doc = BoardDoc.fromJson(board.getDocJson(), boardId);
        }
        doc.setSeq(snapshotSeq);

        List<BoardOpEntity> pending = boardOpRepository
                .findByBoardIdAndSeqGreaterThanOrderBySeqAsc(boardId, snapshotSeq);
        for (BoardOpEntity op : pending) {
            if (!"board".equals(op.getCh())) {
                continue;
            }
            try {
                JsonNode cmd = objectMapper.readTree(op.getCmdJson());
                doc.apply(cmd.path("t").asText(null), cmd);
            } catch (RuntimeException | JsonProcessingException e) {
                log.warn("恢复 op 失败 board={} seq={}：{}", boardId, op.getSeq(), e.getMessage());
            }
            // 即使单条损坏也推进 seq，避免后续恢复时反复卡在同一位置。
            doc.setSeq(op.getSeq());
        }
        return doc;
    }

    /** 异步写入 wb_board_op；同一进程内单线程顺序落库，降低主键乱序概率。 */
    public void appendOp(String boardId, long seq, String actorId, String ch, JsonNode cmd) {
        Objects.requireNonNull(cmd, "cmd");
        final String cmdJson;
        try {
            cmdJson = objectMapper.writeValueAsString(cmd);
        } catch (JsonProcessingException e) {
            log.error("op 序列化失败 board={} seq={}", boardId, seq, e);
            return;
        }

        BoardOpEntity entity = new BoardOpEntity();
        entity.setBoardId(boardId);
        entity.setSeq(seq);
        entity.setActorId(actorId);
        entity.setCh(ch);
        entity.setCmdJson(cmdJson);
        entity.setCreatedAt(Instant.now());

        try {
            opWriter.execute(() -> {
                try {
                    boardOpRepository.save(entity);
                } catch (RuntimeException e) {
                    log.error("op 落库失败 board={} seq={}", boardId, seq, e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("op 写线程已关闭，丢弃 board={} seq={}", boardId, seq);
        }
    }

    public void appendOp(String boardId, OpRecord op) {
        appendOp(boardId, op.seq(), op.actor(), op.ch(), op.cmd());
    }

    /** 读取最近一次快照落库时间，供 WS init.savedAt 使用。 */
    @Transactional(readOnly = true)
    public Instant lastSavedAt(String boardId) {
        if (boardId == null || boardId.isBlank()) {
            return null;
        }
        return boardRepository.findById(boardId)
                .map(BoardEntity::getLastSavedAt)
                .orElse(null);
    }

    /**
     * 请求快照：2 秒防抖，或累计 50 条 op 立即写。
     * 实际写入在单独线程完成，网络路径不阻塞在数据库上。
     */
    public void saveSnapshot(String boardId, BoardDoc doc, long seq) {
        saveSnapshot(boardId, doc, seq, null);
    }

    /**
     * 带落库回调的快照入口：回调在保存成功后于快照线程执行，参数为 (seq, savedAt)，
     * 供 MessageRouter 向房间广播 saved 消息。
     */
    public void saveSnapshot(String boardId, BoardDoc doc, long seq,
                             BiConsumer<Long, Instant> onSaved) {
        if (boardId == null || doc == null) {
            return;
        }
        SnapshotSlot slot = snapshotSlots.computeIfAbsent(boardId, id -> new SnapshotSlot());
        boolean writeNow = false;
        synchronized (slot) {
            if (onSaved != null) {
                // 同一 board 的多次快照请求共享一个槽，回调只需保留最新的广播动作。
                slot.onSaved = onSaved;
            }
            slot.pending++;
            if (slot.pending >= SNAPSHOT_OP_THRESHOLD) {
                slot.pending = 0;
                if (slot.timer != null) {
                    slot.timer.cancel(false);
                    slot.timer = null;
                }
                writeNow = true;
            } else if (slot.timer == null) {
                slot.timer = snapshotScheduler.schedule(() -> {
                    synchronized (slot) {
                        slot.timer = null;
                        slot.pending = 0;
                    }
                    persistSnapshot(boardId, doc, slot);
                }, SNAPSHOT_DEBOUNCE_SECONDS, TimeUnit.SECONDS);
            }
        }
        if (writeNow) {
            try {
                snapshotScheduler.execute(() -> persistSnapshot(boardId, doc, slot));
            } catch (RejectedExecutionException e) {
                log.warn("快照线程已关闭，跳过 board={} seq={}", boardId, seq);
            }
        }
    }

    /** 从 wb_board_op 读取 fromSeq 之后的 op，供服务层重放/排查。 */
    public List<OpRecord> replay(String boardId, long fromSeq) {
        List<BoardOpEntity> rows = boardOpRepository
                .findByBoardIdAndSeqGreaterThanOrderBySeqAsc(boardId, fromSeq);
        List<OpRecord> result = new ArrayList<>(rows.size());
        for (BoardOpEntity row : rows) {
            if (!"board".equals(row.getCh())) {
                continue;
            }
            try {
                JsonNode cmd = objectMapper.readTree(row.getCmdJson());
                result.add(new OpRecord(row.getSeq(), row.getActorId(), row.getCh(), cmd));
            } catch (JsonProcessingException e) {
                log.warn("读取 op 失败 board={} seq={}", boardId, row.getSeq(), e);
            }
        }
        return result;
    }

    private void persistSnapshot(String boardId, BoardDoc doc, SnapshotSlot slot) {
        try {
            BoardDoc.Snapshot snapshot = doc.snapshot();
            BoardEntity board = boardRepository.findById(boardId).orElse(null);
            if (board == null) {
                log.warn("快照写入跳过：board 不存在 {}", boardId);
                return;
            }
            // 回滚会把 doc_seq 推得比在途快照更大；旧快照不能反向覆盖新文档。
            if (snapshot.seq() < board.getDocSeq()) {
                log.debug("丢弃过期快照 board={} snapshotSeq={} boardSeq={}", boardId,
                        snapshot.seq(), board.getDocSeq());
                return;
            }
            Instant now = Instant.now();
            board.setDocJson(snapshot.json());
            board.setDocSeq(snapshot.seq());
            board.setUpdatedAt(now);
            board.setLastSavedAt(now);
            boardRepository.save(board);

            // ATTENTION：本方法运行在快照线程（非请求线程），自动版本判定不阻塞 WS/REST。
            boardVersionService.maybeCreateAutoVersion(boardId, snapshot.json(), snapshot.seq(), now);

            BiConsumer<Long, Instant> callback = slot == null ? null : slot.onSaved;
            if (callback != null) {
                try {
                    callback.accept(snapshot.seq(), now);
                } catch (RuntimeException e) {
                    log.warn("快照回调执行失败 board={}：{}", boardId, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.error("快照写入失败 board={}", boardId, e);
        }
    }

    /** 旧库/旧调用可能没有成员行，首次加载时补写 owner，避免作品库漏项。 */
    private void ensureOwnerMember(String boardId, String ownerId) {
        if (ownerId == null || SYSTEM_OWNER.equals(ownerId)) {
            return;
        }
        if (boardMemberRepository.findByBoardIdAndUserId(boardId, ownerId).isPresent()) {
            return;
        }
        boardMemberRepository.save(new BoardMemberEntity(boardId, ownerId, "OWNER", Instant.now()));
    }

    @PreDestroy
    public void shutdown() {
        snapshotSlots.values().forEach(slot -> {
            synchronized (slot) {
                if (slot.timer != null) {
                    slot.timer.cancel(false);
                    slot.timer = null;
                }
            }
        });
        snapshotScheduler.shutdown();
        opWriter.shutdown();
        try {
            if (!snapshotScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                snapshotScheduler.shutdownNow();
            }
            if (!opWriter.awaitTermination(2, TimeUnit.SECONDS)) {
                opWriter.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            snapshotScheduler.shutdownNow();
            opWriter.shutdownNow();
        }
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void requireBoardId(String boardId) {
        if (boardId == null || boardId.isBlank() || boardId.length() > 32) {
            throw new IllegalArgumentException("boardId 非法");
        }
    }

    private static final class SnapshotSlot {
        private int pending;
        private ScheduledFuture<?> timer;
        private volatile BiConsumer<Long, Instant> onSaved;
    }
}
