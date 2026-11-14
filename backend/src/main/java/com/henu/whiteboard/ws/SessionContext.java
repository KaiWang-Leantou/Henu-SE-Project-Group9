package com.henu.whiteboard.ws;

import com.henu.whiteboard.room.Member;
import com.henu.whiteboard.room.MemberColors;
import com.henu.whiteboard.room.Room;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单条 WebSocket 连接的服务端上下文：身份、所在房间、光标节流与未结束笔划缓存。
 */
public class SessionContext {

    private static final long CURSOR_MIN_INTERVAL_NANOS = 33_000_000L; // 约 30Hz

    private final WebSocketSession webSocketSession;
    /** 握手 URL 上的 ?token=，鉴权时优先于 join.token。 */
    private final String queryToken;
    private final Map<String, StrokeDraft> drafts = new ConcurrentHashMap<>();
    private final Map<String, Long> endedStrokes = new ConcurrentHashMap<>();
    private final AtomicLong lastCursorNanos = new AtomicLong(0L);

    private volatile String userId;
    private volatile String nickname;
    private volatile boolean guest = true;
    private volatile String role; // null 表示尚未 join
    private volatile String boardId;
    private volatile Room room;
    private volatile long joinedAt;
    private volatile long lastSeenAt = System.currentTimeMillis();
    /** 房间内分配的光标色；未分配时回落到 userId 哈希色（docs/07 §3）。 */
    private volatile String cursorColor;

    /** 由 Room 在入房时调用，保证同房间内颜色尽量不重复。 */
    public void assignCursorColor(String color) {
        this.cursorColor = color;
    }

    public SessionContext(WebSocketSession webSocketSession) {
        this.webSocketSession = Objects.requireNonNull(webSocketSession, "webSocketSession");
        this.userId = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        this.nickname = defaultNickname();
        this.queryToken = extractQueryToken(webSocketSession.getUri());
    }

    public WebSocketSession webSocketSession() {
        return webSocketSession;
    }

    public String queryToken() {
        return queryToken;
    }

    public String userId() {
        return userId;
    }

    public String nickname() {
        return nickname;
    }

    public boolean guest() {
        return guest;
    }

    public String role() {
        return role;
    }

    public String boardId() {
        return boardId;
    }

    public Room room() {
        return room;
    }

    public boolean isJoined() {
        return role != null && room != null;
    }

    public long joinedAt() {
        return joinedAt;
    }

    public void touch() {
        lastSeenAt = System.currentTimeMillis();
    }

    public long lastSeenAt() {
        return lastSeenAt;
    }

    /** join 前用 JWT 身份覆盖握手时的临时身份。 */
    public synchronized void authenticate(String userId, String nickname, boolean guest) {
        if (userId != null && !userId.isBlank()) {
            this.userId = userId;
        }
        if (nickname != null && !nickname.isBlank()) {
            this.nickname = normalizeNickname(nickname);
        }
        this.guest = guest;
    }

    /** 加入房间；未调用前 role 一直为 null。 */
    public synchronized void join(Room room, String nickname, String role) {
        this.room = Objects.requireNonNull(room, "room");
        this.boardId = room.boardId();
        if (nickname != null && !nickname.isBlank()) {
            this.nickname = normalizeNickname(nickname);
        }
        this.role = role;
        this.joinedAt = System.currentTimeMillis();
        this.drafts.clear();
        this.endedStrokes.clear();
    }

    /** 离开房间并清理临时笔划；重复调用安全。 */
    public synchronized void leave() {
        this.role = null;
        this.room = null;
        this.boardId = null;
        this.cursorColor = null;
        this.drafts.clear();
        this.endedStrokes.clear();
    }

    public Member toMember() {
        String currentRole = role;
        if (currentRole == null) {
            return null;
        }
        String color = cursorColor;
        return new Member(userId, nickname, currentRole, true, guest,
                color != null ? color : MemberColors.of(userId));
    }

    /** 服务端丢弃超过 30Hz 的光标，静默处理（协议允许直接丢弃）。 */
    public boolean tryAcquireCursor() {
        long now = System.nanoTime();
        long previous = lastCursorNanos.get();
        if (previous != 0L && now - previous < CURSOR_MIN_INTERVAL_NANOS) {
            return false;
        }
        return lastCursorNanos.compareAndSet(previous, now);
    }

    public StrokeDraft draft(String strokeId) {
        return drafts.get(strokeId);
    }

    public void putDraft(StrokeDraft draft) {
        drafts.put(draft.strokeId(), draft);
    }

    public StrokeDraft removeDraft(String strokeId) {
        return drafts.remove(strokeId);
    }

    /** 记录已定序的笔划，供重复 stroke.end 幂等回 ack。 */
    public void markStrokeEnded(String strokeId, long seq) {
        if (endedStrokes.size() > 64) {
            endedStrokes.clear();
        }
        endedStrokes.put(strokeId, seq);
    }

    public Long endedStrokeSeq(String strokeId) {
        return endedStrokes.get(strokeId);
    }

    private static String defaultNickname() {
        return "游客" + ThreadLocalRandom.current().nextInt(1000, 10000);
    }

    private static String normalizeNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return defaultNickname();
        }
        String trimmed = nickname.trim();
        return trimmed.length() <= 50 ? trimmed : trimmed.substring(0, 50);
    }

    /** 从握手 URL 解析 ?token=；query 优先于 join.token。 */
    private static String extractQueryToken(URI uri) {
        if (uri == null || uri.getRawQuery() == null || uri.getRawQuery().isBlank()) {
            return null;
        }
        for (String pair : uri.getRawQuery().split("&")) {
            int index = pair.indexOf('=');
            if (index <= 0) {
                continue;
            }
            String name = URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8);
            if (!"token".equals(name)) {
                continue;
            }
            String value = URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    /**
     * 一条正在绘制、尚未 stroke.end 的笔划。points 为扁平坐标数组。
     */
    public record StrokeDraft(String pageId, String strokeId, String tool, String color,
                              double width, List<Double> points, long createdAt) {

        public StrokeDraft {
            pageId = Objects.requireNonNull(pageId, "pageId");
            strokeId = Objects.requireNonNull(strokeId, "strokeId");
            tool = Objects.requireNonNull(tool, "tool");
            color = Objects.requireNonNull(color, "color");
            points = List.copyOf(points);
        }

        public StrokeDraft append(List<Double> more) {
            List<Double> merged = new ArrayList<>(points.size() + more.size());
            merged.addAll(points);
            merged.addAll(more);
            return new StrokeDraft(pageId, strokeId, tool, color, width, merged, createdAt);
        }
    }
}
