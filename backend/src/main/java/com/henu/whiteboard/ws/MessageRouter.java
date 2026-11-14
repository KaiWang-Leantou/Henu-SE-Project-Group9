package com.henu.whiteboard.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.henu.whiteboard.auth.JwtPayload;
import com.henu.whiteboard.auth.JwtService;
import com.henu.whiteboard.board.BoardAccessService;
import com.henu.whiteboard.board.BoardDoc;
import com.henu.whiteboard.board.BoardService;
import com.henu.whiteboard.board.OpRecord;
import com.henu.whiteboard.common.ApiException;
import com.henu.whiteboard.persistence.BoardEntity;
import com.henu.whiteboard.persistence.UserEntity;
import com.henu.whiteboard.persistence.UserRepository;
import com.henu.whiteboard.plugin.PluginRegistry;
import com.henu.whiteboard.room.Member;
import com.henu.whiteboard.room.Room;
import com.henu.whiteboard.room.RoomRegistry;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/**
 * 协议路由：校验 join/op/stroke/cursor/ping，并把 board op 送进房间串行执行器。
 */
@Component
public class MessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final int MAX_REPLAY_GAP = 200;
    private static final int MAX_APPEND_FLAT_NUMBERS = 1024; // 512 个坐标点
    private static final int MAX_STROKE_FLAT_NUMBERS = 8000;  // 4000 个坐标点
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final BoardService boardService;
    private final RoomRegistry roomRegistry;
    private final PluginRegistry pluginRegistry;
    private final Broadcaster broadcaster;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final BoardAccessService boardAccessService;

    public MessageRouter(BoardService boardService,
                         RoomRegistry roomRegistry,
                         PluginRegistry pluginRegistry,
                         Broadcaster broadcaster,
                         JwtService jwtService,
                         UserRepository userRepository,
                         BoardAccessService boardAccessService) {
        this.boardService = boardService;
        this.roomRegistry = roomRegistry;
        this.pluginRegistry = pluginRegistry;
        this.broadcaster = broadcaster;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.boardAccessService = boardAccessService;
    }

    public void handle(SessionContext context, JsonNode message) {
        String cid = textOrNull(message, "cid");
        try {
            if (context == null) {
                return;
            }
            context.touch();
            if (message == null || !message.isObject()) {
                throw bad("消息必须是 JSON 对象");
            }
            String type = requireText(message, "t", 32);
            if (!"join".equals(type) && !context.isJoined()) {
                throw unauthorized("请先 join 房间");
            }
            switch (type) {
                case "join" -> onJoin(context, message);
                case "op" -> onOp(context, message);
                case "stroke.append" -> onStrokeAppend(context, message);
                case "stroke.end" -> onStrokeEnd(context, message);
                case "cursor" -> onCursor(context, message);
                case "ping" -> onPing(context);
                default -> throw bad("未知消息类型: " + type);
            }
        } catch (ProtocolException e) {
            broadcaster.sendError(context, cid, e.code, e.getMessage());
            if (e.closeConnection) {
                closeQuietly(context);
            }
        } catch (ApiException e) {
            // 复用 REST 的错误码（FORBIDDEN/NOT_FOUND…）转成 WS error
            broadcaster.sendError(context, cid, e.code().name(), e.getMessage());
        } catch (IllegalArgumentException e) {
            broadcaster.sendError(context, cid, "BAD_REQUEST", e.getMessage());
        } catch (Exception e) {
            log.error("处理 WS 消息异常 session={}", context == null ? "-"
                    : context.webSocketSession().getId(), e);
            broadcaster.sendError(context, cid, "INTERNAL", "服务端内部错误");
        }
    }

    /** 连接关闭时清理成员并广播 presence；最后一个成员离开时释放房间。 */
    public void onDisconnect(SessionContext context) {
        if (context == null) {
            return;
        }
        Room room = context.room();
        context.leave();
        if (room == null) {
            return;
        }
        room.removeMember(context);
        if (!room.isEmpty()) {
            broadcaster.broadcastPresence(room);
        }
        roomRegistry.removeIfEmpty(room.boardId(), room);
    }

    /**
     * 加入房间：先鉴权（query token 优先，其次 join.token），再按 wb_board_member 判角色；
     * 不存在的板按 docs/05 §4 自动创建（owner=当前用户，status=DRAFT）。
     */
    private void onJoin(SessionContext context, JsonNode message) {
        if (context.isJoined()) {
            leaveCurrentRoom(context);
        }
        String boardId = requireText(message, "boardId", 32);
        long lastSeq = optionalNonNegativeLong(message, "lastSeq", 0L);

        String token = resolveToken(context, message);
        if (token == null) {
            throw authRequired("缺少访问令牌，请先登录");
        }
        final JwtPayload payload;
        try {
            payload = jwtService.parse(token);
        } catch (ExpiredJwtException e) {
            throw authRequired("登录已过期，请重新登录");
        } catch (JwtException | IllegalArgumentException e) {
            throw authRequired("访问令牌无效");
        }
        UserEntity user = userRepository.findById(payload.userId()).orElse(null);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw authRequired("账号不存在或已被禁用");
        }

        // 不存在的板自动建；已存在的板先判读权限，私有板非成员直接 403。
        BoardEntity board = boardAccessService.findOrCreateForJoin(boardId, user.getId());
        String role = boardAccessService.requireReadRole(board, user.getId());

        BoardDoc loaded = boardService.loadOrCreate(boardId, user.getId());
        Room room = roomRegistry.getOrCreate(boardId, loaded);
        // 拿库里的最新状态刷新房间只读标记（板可能在两次 join 之间被归档）
        room.setStatus(board.getStatus());
        context.authenticate(user.getId(), user.getNickname(), user.isGuest());
        context.join(room, user.getNickname(), role);
        room.addMember(context);

        Member me = context.toMember();
        BoardDoc.NodeSnapshot state = room.doc().nodeSnapshot();
        long currentSeq = state.seq();
        List<OpRecord> gapOps = List.of();
        if (lastSeq > 0 && currentSeq > lastSeq) {
            long gap = currentSeq - lastSeq;
            if (gap <= MAX_REPLAY_GAP && room.canReplayFrom(lastSeq)) {
                gapOps = room.opsAfter(lastSeq);
            }
        }

        // 先补发缺口，再发当前快照 init（含 guest 与 savedAt），客户端最终以 init 对齐。
        for (OpRecord op : gapOps) {
            broadcaster.send(context, Out.op(op.seq(), op.actor(), op.ch(), op.cmd()));
        }
        broadcaster.send(context, Out.init(boardId, currentSeq, state.doc(), me, room.members(),
                boardService.lastSavedAt(boardId)));
        broadcaster.broadcastPresence(room);
        pluginRegistry.fireJoin(room, me);
    }

    /** token 解析顺序：握手 query ?token= 优先，其次 join.token。 */
    private static String resolveToken(SessionContext context, JsonNode message) {
        String queryToken = context.queryToken();
        if (queryToken != null && !queryToken.isBlank()) {
            return queryToken.trim();
        }
        String joinToken = optionalText(message, "token", 4096);
        if (joinToken == null || joinToken.isBlank()) {
            return null;
        }
        return joinToken.trim();
    }

    private void onPing(SessionContext context) {
        requireJoined(context);
        broadcaster.send(context, Out.pong());
    }

    private void onCursor(SessionContext context, JsonNode message) {
        Room room = requireJoined(context);
        double x = requireFiniteNumber(message, "x");
        double y = requireFiniteNumber(message, "y");
        if (!context.tryAcquireCursor()) {
            return; // 超过 30Hz 的光标直接丢弃
        }
        broadcaster.broadcast(room, Out.cursor(context.userId(), x, y));
    }

    private void onStrokeAppend(SessionContext context, JsonNode message) {
        Room room = requireJoined(context);
        requireWritable(room);
        Member member = context.toMember();
        if (member == null || !member.canEdit()) {
            throw forbidden("当前角色不能绘制");
        }

        String pageId = requireText(message, "pageId", 32);
        if (!room.doc().hasPage(pageId)) {
            throw notFound("页面不存在");
        }
        String strokeId = requireText(message, "strokeId", 32);
        String tool = requireText(message, "tool", 8);
        if (!"pen".equals(tool)) {
            throw bad("tool 目前只支持 pen");
        }
        String color = requireColor(message, "color");
        double width = requireRange(message, "width", 1, 64);
        JsonNode ptsNode = message.get("pts");
        List<Double> pts = requirePts(ptsNode, MAX_APPEND_FLAT_NUMBERS);
        if (!pluginRegistry.canWrite(room, member, "stroke.append", message)) {
            throw forbidden("当前阶段不可绘制");
        }

        SessionContext.StrokeDraft existing = context.draft(strokeId);
        SessionContext.StrokeDraft draft;
        if (existing == null) {
            draft = new SessionContext.StrokeDraft(pageId, strokeId, tool, color, width,
                    pts, System.currentTimeMillis());
        } else {
            if (!existing.pageId().equals(pageId)
                    || !existing.tool().equals(tool)
                    || !existing.color().equals(color)
                    || existing.width() != width) {
                throw bad("同一 strokeId 的笔划元数据不一致");
            }
            if (existing.points().size() + pts.size() > MAX_STROKE_FLAT_NUMBERS) {
                throw bad("单笔点数超过上限");
            }
            draft = existing.append(pts);
        }
        context.putDraft(draft);
        broadcaster.broadcast(room, Out.strokeAppend(member.id(), pageId, strokeId,
                tool, color, width, ptsNode));
    }

    private void onStrokeEnd(SessionContext context, JsonNode message) {
        Room room = requireJoined(context);
        requireWritable(room);
        Member member = context.toMember();
        if (member == null || !member.canEdit()) {
            throw forbidden("当前角色不能绘制");
        }
        String cid = requireText(message, "cid", 128);
        String strokeId = requireText(message, "strokeId", 32);

        SessionContext.StrokeDraft draft = context.draft(strokeId);
        if (draft == null) {
            Long endedSeq = context.endedStrokeSeq(strokeId);
            if (endedSeq != null) {
                // 重复 stroke.end 幂等：回原 seq，不重复分配序号。
                broadcaster.send(context, Out.ack(cid, endedSeq));
                return;
            }
            throw bad("未找到待结束的笔划");
        }
        if (!room.doc().hasPage(draft.pageId())) {
            throw notFound("页面不存在");
        }

        ObjectNode cmd = buildStrokeAdd(member, draft);
        room.doc().validate("stroke.add", cmd);
        if (!pluginRegistry.canWrite(room, member, "stroke.add", cmd)) {
            throw forbidden("当前阶段不可绘制");
        }
        context.removeDraft(strokeId);
        submitBoardOp(context, room, member, cmd, cid, strokeId);
    }

    private void onOp(SessionContext context, JsonNode message) {
        Room room = requireJoined(context);
        requireWritable(room);
        Member member = context.toMember();
        if (member == null) {
            throw unauthorized("请先 join 房间");
        }
        String cid = requireText(message, "cid", 128);
        String ch = requireText(message, "ch", 16);
        JsonNode cmdNode = message.get("cmd");
        if (cmdNode == null || !cmdNode.isObject()) {
            throw bad("cmd 必须是对象");
        }
        if ("game".equals(ch)) {
            boolean handled = pluginRegistry.dispatch(ch, room, member, message,
                    reply -> broadcaster.send(context, reply));
            if (!handled) {
                throw bad("game 通道未注册插件");
            }
            return;
        }
        if (!"board".equals(ch)) {
            throw bad("未知通道: " + ch);
        }

        String cmdType = requireText(cmdNode, "t", 32);
        if (!member.canEdit()) {
            throw forbidden("当前角色不能编辑白板");
        }
        if (ownerOnly(cmdType) && !member.isOwner()) {
            throw forbidden("该命令仅房主可执行");
        }
        String pageId = null;
        if (requiresPage(cmdType)) {
            pageId = requireText(cmdNode, "pageId", 32);
            if (!room.doc().hasPage(pageId)) {
                throw notFound("页面不存在");
            }
        }

        // 字段预检：在进入串行执行器前尽早发现坏命令。
        room.doc().validate(cmdType, cmdNode);
        ensureEraseOwnership(room.doc(), cmdType, cmdNode, member);
        if (!pluginRegistry.canWrite(room, member, cmdType, cmdNode)) {
            throw forbidden("当前阶段不可写入");
        }

        submitBoardOp(context, room, member, ((ObjectNode) cmdNode).deepCopy(), cid, null);
    }

    private void submitBoardOp(SessionContext context, Room room, Member member,
                               ObjectNode cmd, String cid, String endedStrokeId) {
        try {
            room.executor().execute(() ->
                    commitBoardOp(context, room, member, cmd, cid, endedStrokeId));
        } catch (RejectedExecutionException e) {
            broadcaster.sendError(context, cid, "INTERNAL", "房间已关闭，请重新 join");
        }
    }

    /**
     * 串行执行器内的一次提交：应用命令、分配 seq、广播、ack、异步落库。
     */
    private void commitBoardOp(SessionContext context, Room room, Member actor,
                               ObjectNode cmd, String cid, String endedStrokeId) {
        try {
            if (!room.hasMember(context)) {
                throw unauthorized("连接已离开房间");
            }
            Member latest = context.toMember();
            if (latest == null) {
                throw unauthorized("请先 join 房间");
            }
            // 排队期间板可能已被归档/删除：串行执行前再校验一次，防止漏网写入
            requireWritable(room);
            String cmdType = requireText(cmd, "t", 32);
            if (!latest.canEdit()) {
                throw forbidden("当前角色不能编辑白板");
            }
            if (ownerOnly(cmdType) && !latest.isOwner()) {
                throw forbidden("该命令仅房主可执行");
            }
            if (requiresPage(cmdType)) {
                String pageId = requireText(cmd, "pageId", 32);
                if (!room.doc().hasPage(pageId)) {
                    throw notFound("页面不存在");
                }
            }
            room.doc().validate(cmdType, cmd);
            ensureEraseOwnership(room.doc(), cmdType, cmd, latest);
            if (!pluginRegistry.canWrite(room, latest, cmdType, cmd)) {
                throw forbidden("当前阶段不可写入");
            }

            long seq = room.nextSeq();
            room.doc().apply(cmdType, cmd, seq);
            OpRecord record = new OpRecord(seq, latest.id(), "board", cmd);
            room.recordOp(record);

            // 先广播 stroke.end（如果本次由抬笔触发），再广播定序 op；随后 ack 并异步落库。
            if (endedStrokeId != null) {
                broadcaster.broadcast(room, Out.strokeEnd(latest.id(), endedStrokeId, seq));
                context.markStrokeEnded(endedStrokeId, seq);
            }
            broadcaster.broadcast(room, Out.op(seq, latest.id(), "board", cmd));
            broadcaster.send(context, Out.ack(cid, seq));

            pluginRegistry.fireCommitted(room, latest, seq, "board", cmd);
            boardService.appendOp(room.boardId(), seq, latest.id(), "board", cmd);
            boardService.saveSnapshot(room.boardId(), room.doc(), seq, (savedSeq, savedAt) -> {
                // 快照是异步的，广播时以注册表里的当前房间为准（房间可能已被重建）
                Room current = roomRegistry.get(room.boardId());
                broadcaster.broadcast(current == null ? room : current, Out.saved(savedSeq, savedAt));
            });
        } catch (ProtocolException e) {
            broadcaster.sendError(context, cid, e.code, e.getMessage());
        } catch (IllegalArgumentException e) {
            broadcaster.sendError(context, cid, "BAD_REQUEST", e.getMessage());
        } catch (Exception e) {
            log.error("串行提交异常 board={} session={}", room.boardId(),
                    context.webSocketSession().getId(), e);
            broadcaster.sendError(context, cid, "INTERNAL", "服务端内部错误");
        }
    }

    private ObjectNode buildStrokeAdd(Member member, SessionContext.StrokeDraft draft) {
        ObjectNode cmd = NODES.objectNode();
        cmd.put("t", "stroke.add");
        cmd.put("pageId", draft.pageId());
        ObjectNode stroke = cmd.putObject("stroke");
        stroke.put("kind", "stroke");
        stroke.put("id", draft.strokeId());
        stroke.put("author", member.id());
        stroke.put("createdAt", draft.createdAt());
        stroke.put("tool", draft.tool());
        stroke.put("color", draft.color());
        stroke.put("width", draft.width());
        ArrayNode points = stroke.putArray("points");
        for (Double point : draft.points()) {
            points.add(point);
        }
        return cmd;
    }

    private void leaveCurrentRoom(SessionContext context) {
        Room oldRoom = context.room();
        context.leave();
        if (oldRoom == null) {
            return;
        }
        oldRoom.removeMember(context);
        if (!oldRoom.isEmpty()) {
            broadcaster.broadcastPresence(oldRoom);
        }
        roomRegistry.removeIfEmpty(oldRoom.boardId(), oldRoom);
    }

    private Room requireJoined(SessionContext context) {
        if (context == null || !context.isJoined()) {
            throw unauthorized("请先 join 房间");
        }
        Room room = context.room();
        if (room == null || room.isClosed() || !room.hasMember(context)) {
            throw unauthorized("房间会话已失效，请重新 join");
        }
        return room;
    }

    /** 归档 / 回收站板：所有 WS 写命令统一回 READ_ONLY（docs/07 §2、§3）。 */
    private static void requireWritable(Room room) {
        if (room != null && room.isReadOnly()) {
            throw readOnly("白板已归档或在回收站，只读");
        }
    }

    private void ensureEraseOwnership(BoardDoc doc, String cmdType, JsonNode cmd, Member member) {
        if (!"stroke.erase".equals(cmdType) || member.isOwner()) {
            return;
        }
        String pageId = requireText(cmd, "pageId", 32);
        JsonNode ids = cmd.get("ids");
        if (ids == null || !ids.isArray()) {
            return; // 字段格式由 BoardDoc 校验统一报 BAD_REQUEST
        }
        for (JsonNode idNode : ids) {
            if (!idNode.isTextual()) {
                continue;
            }
            String author = doc.authorOfElement(pageId, idNode.asText());
            if (author != null && !author.equals(member.id())) {
                throw forbidden("只能擦除自己绘制的笔划");
            }
        }
    }

    private static boolean requiresPage(String cmdType) {
        return switch (cmdType) {
            case "stroke.add", "stroke.erase", "object.add", "object.patch",
                    "object.remove", "page.rename", "page.clear" -> true;
            default -> false;
        };
    }

    private static boolean ownerOnly(String cmdType) {
        return "page.clear".equals(cmdType) || "board.reset".equals(cmdType);
    }

    private List<Double> requirePts(JsonNode ptsNode, int maxFlatNumbers) {
        if (ptsNode == null || !ptsNode.isArray()) {
            throw bad("pts 必须是扁平数组");
        }
        int size = ptsNode.size();
        if (size < 2 || size % 2 != 0 || size > maxFlatNumbers) {
            throw bad("pts 长度非法");
        }
        List<Double> result = new java.util.ArrayList<>(size);
        for (JsonNode point : ptsNode) {
            if (!point.isNumber() || !Double.isFinite(point.asDouble())) {
                throw bad("pts 含非法坐标");
            }
            result.add(point.asDouble());
        }
        return result;
    }

    private static String requireText(JsonNode message, String field, int maxLength) {
        JsonNode node = message.get(field);
        if (node == null || !node.isTextual()) {
            throw bad(field + " 必须是字符串");
        }
        String value = node.asText();
        if (value.isBlank() || value.length() > maxLength) {
            throw bad(field + " 长度非法");
        }
        return value;
    }

    private static String requireColor(JsonNode message, String field) {
        JsonNode node = message.get(field);
        if (node == null || !node.isTextual() || !COLOR.matcher(node.asText()).matches()) {
            throw bad(field + " 必须是 #RRGGBB");
        }
        return node.asText();
    }

    private static double requireRange(JsonNode message, String field, double min, double max) {
        double value = requireFiniteNumber(message, field);
        if (value < min || value > max) {
            throw bad(field + " 超出范围");
        }
        return value;
    }

    private static double requireFiniteNumber(JsonNode message, String field) {
        JsonNode node = message.get(field);
        if (node == null || !node.isNumber() || !Double.isFinite(node.asDouble())) {
            throw bad(field + " 必须是有限数值");
        }
        return node.asDouble();
    }

    private static long optionalNonNegativeLong(JsonNode message, String field, long defaultValue) {
        JsonNode node = message.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isIntegralNumber() || node.asLong() < 0) {
            throw bad(field + " 必须是非负整数");
        }
        return node.asLong();
    }

    private static String optionalText(JsonNode message, String field, int maxLength) {
        JsonNode node = message.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual() || node.asText().length() > maxLength) {
            throw bad(field + " 长度非法");
        }
        return node.asText();
    }

    private static String textOrNull(JsonNode message, String field) {
        if (message == null || !message.isObject()) {
            return null;
        }
        JsonNode node = message.get(field);
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static ProtocolException unauthorized(String message) {
        return new ProtocolException("UNAUTHORIZED", message);
    }

    private static ProtocolException forbidden(String message) {
        return new ProtocolException("FORBIDDEN", message);
    }

    private static ProtocolException readOnly(String message) {
        return new ProtocolException("READ_ONLY", message);
    }

    private static ProtocolException notFound(String message) {
        return new ProtocolException("NOT_FOUND", message);
    }

    private static ProtocolException bad(String message) {
        return new ProtocolException("BAD_REQUEST", message);
    }

    /** 缺少/非法 token：回 AUTH_REQUIRED 后必须关闭连接。 */
    private static ProtocolException authRequired(String message) {
        return new ProtocolException("AUTH_REQUIRED", message, true);
    }

    private void closeQuietly(SessionContext context) {
        WebSocketSession session = context == null ? null : context.webSocketSession();
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException e) {
            log.debug("关闭 WS 失败 session={}：{}", session.getId(), e.getMessage());
        }
    }

    /** 协议错误；由 handle 统一转成 error 消息。 */
    private static final class ProtocolException extends RuntimeException {
        private final String code;
        private final boolean closeConnection;

        private ProtocolException(String code, String message) {
            this(code, message, false);
        }

        private ProtocolException(String code, String message, boolean closeConnection) {
            super(message);
            this.code = code;
            this.closeConnection = closeConnection;
        }
    }
}
