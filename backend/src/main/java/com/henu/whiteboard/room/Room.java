package com.henu.whiteboard.room;

import com.henu.whiteboard.board.BoardDoc;
import com.henu.whiteboard.board.BoardStatus;
import com.henu.whiteboard.board.OpRecord;
import com.henu.whiteboard.ws.SessionContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一个 board 的内存房间：成员表、全序 seq、最近 op 环形缓冲和单线程串行执行器。
 */
public class Room implements AutoCloseable {

    /** 每个房间保留的 op 条数；重连缺口超过此范围时客户端应走全量 init。 */
    public static final int OP_BUFFER_SIZE = 500;

    private final String boardId;
    private final BoardDoc doc;
    private final AtomicLong seq;
    private final SerialExecutor executor;
    private final Deque<OpRecord> opBuffer = new ArrayDeque<>(OP_BUFFER_SIZE);
    private final ConcurrentHashMap<String, SessionContext> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** 板生命周期状态的进程内缓存：join 时刷新，归档/回收站/恢复时由生命周期服务更新。 */
    private volatile String status;

    public Room(String boardId, BoardDoc doc) {
        this(boardId, doc, null);
    }

    public Room(String boardId, BoardDoc doc, String status) {
        this.boardId = Objects.requireNonNull(boardId, "boardId");
        this.doc = Objects.requireNonNull(doc, "doc");
        if (!boardId.equals(doc.boardId())) {
            throw new IllegalArgumentException("Room 与文档的 boardId 不一致");
        }
        this.seq = new AtomicLong(doc.seq());
        this.executor = new SerialExecutor(boardId);
        this.status = status;
    }

    public String boardId() {
        return boardId;
    }

    public BoardDoc doc() {
        return doc;
    }

    public long seq() {
        return seq.get();
    }

    public String status() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    /** ARCHIVED / TRASHED 的板在 WS 层一律只读（docs/07 §2）。 */
    public boolean isReadOnly() {
        String current = status;
        return BoardStatus.ARCHIVED.equals(current) || BoardStatus.TRASHED.equals(current);
    }

    /** 回滚后整体替换房间权威文档，并把 seq 推进到不落后于新快照。 */
    public void adopt(String docJson, long newSeq) {
        doc.replaceWith(docJson, newSeq);
        seq.updateAndGet(current -> Math.max(current, newSeq));
    }

    /** 只在房间串行线程中调用；返回连续的新序号。 */
    public long nextSeq() {
        return seq.incrementAndGet();
    }

    public SerialExecutor executor() {
        return executor;
    }

    public void addMember(SessionContext session) {
        Objects.requireNonNull(session, "session");
        if (closed.get()) {
            throw new IllegalStateException("房间已关闭");
        }
        session.assignCursorColor(pickCursorColor(session.userId()));
        sessions.put(session.webSocketSession().getId(), session);
    }

    /**
     * 光标颜色分配（docs/07 §3）：优先用 userId 哈希得到的稳定色；
     * 若该色已被房间内其他人占用，则取色板中第一个空闲色，避免两人光标同色。
     */
    private String pickCursorColor(String userId) {
        Set<String> used = new HashSet<>();
        for (SessionContext other : sessions.values()) {
            Member member = other.isJoined() ? other.toMember() : null;
            if (member != null && member.color() != null) {
                used.add(member.color());
            }
        }
        String preferred = MemberColors.of(userId);
        if (!used.contains(preferred)) {
            return preferred;
        }
        for (String candidate : MemberColors.palette()) {
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        return preferred;
    }

    public void removeMember(SessionContext session) {
        if (session == null) {
            return;
        }
        sessions.remove(session.webSocketSession().getId());
    }

    public boolean hasMember(SessionContext session) {
        return session != null
                && sessions.get(session.webSocketSession().getId()) == session;
    }

    public boolean isEmpty() {
        return sessions.isEmpty();
    }

    public int size() {
        return sessions.size();
    }

    /** 成员快照，按加入时间排序，供 presence/init 全量下发。 */
    public List<Member> members() {
        return sessions.values().stream()
                .filter(SessionContext::isJoined)
                .sorted(Comparator.comparingLong(SessionContext::joinedAt)
                        .thenComparing(SessionContext::userId))
                .map(SessionContext::toMember)
                .toList();
    }

    /** 当前连接快照；广播时遍历它，避免直接暴露内部 map。 */
    public List<SessionContext> sessions() {
        return new ArrayList<>(sessions.values());
    }

    /** 记录已定序 op，只保留最近 OP_BUFFER_SIZE 条。 */
    public void recordOp(OpRecord op) {
        Objects.requireNonNull(op, "op");
        synchronized (opBuffer) {
            opBuffer.addLast(op);
            while (opBuffer.size() > OP_BUFFER_SIZE) {
                opBuffer.removeFirst();
            }
        }
    }

    /**
     * fromSeq 之后的缺口是否都能从环形缓冲补发。
     * fromSeq >= 当前 seq 表示没有缺口，直接返回 true。
     */
    public boolean canReplayFrom(long fromSeq) {
        long current = seq.get();
        if (fromSeq >= current) {
            return true;
        }
        synchronized (opBuffer) {
            if (opBuffer.isEmpty()) {
                return false;
            }
            return opBuffer.peekFirst().seq() <= fromSeq + 1;
        }
    }

    /** 返回 seq 严格大于 fromSeq 的 op 快照，按 seq 升序。 */
    public List<OpRecord> opsAfter(long fromSeq) {
        synchronized (opBuffer) {
            List<OpRecord> result = new ArrayList<>();
            for (OpRecord op : opBuffer) {
                if (op.seq() > fromSeq) {
                    result.add(op);
                }
            }
            return result;
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.close();
        }
    }
}
