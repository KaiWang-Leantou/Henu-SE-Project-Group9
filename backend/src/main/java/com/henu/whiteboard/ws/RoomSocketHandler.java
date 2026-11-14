package com.henu.whiteboard.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 原生 WebSocket 入口：维护连接上下文、解析文本帧并交给 MessageRouter。
 */
@Component
public class RoomSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RoomSocketHandler.class);
    private static final int MAX_FRAME_BYTES = 256 * 1024;

    private final ObjectMapper objectMapper;
    private final MessageRouter messageRouter;
    private final Broadcaster broadcaster;
    private final ConcurrentHashMap<String, SessionContext> contexts = new ConcurrentHashMap<>();

    public RoomSocketHandler(ObjectMapper objectMapper,
                             MessageRouter messageRouter,
                             Broadcaster broadcaster) {
        this.objectMapper = objectMapper;
        this.messageRouter = messageRouter;
        this.broadcaster = broadcaster;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        SessionContext context = new SessionContext(session);
        contexts.put(session.getId(), context);
        log.debug("WS 连接建立 session={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SessionContext context = contexts.computeIfAbsent(session.getId(),
                id -> new SessionContext(session));
        context.touch();

        if (message.getPayloadLength() > MAX_FRAME_BYTES) {
            broadcaster.send(context, Out.error(null, "FRAME_TOO_LARGE", "单帧超过 256KB"));
            closeQuietly(session, CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }

        final JsonNode root;
        try {
            root = objectMapper.readTree(message.getPayload());
        } catch (JsonProcessingException e) {
            broadcaster.sendError(context, null, "BAD_REQUEST", "消息不是合法 JSON");
            return;
        }
        if (root == null || !root.isObject()) {
            broadcaster.sendError(context, null, "BAD_REQUEST", "消息必须是 JSON 对象");
            return;
        }
        messageRouter.handle(context, root);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SessionContext context = contexts.remove(session.getId());
        if (context != null) {
            messageRouter.onDisconnect(context);
        }
        log.debug("WS 连接关闭 session={} status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WS 传输异常 session={}", session.getId(), exception);
        SessionContext context = contexts.remove(session.getId());
        if (context != null) {
            messageRouter.onDisconnect(context);
        }
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("关闭 WS 失败 session={}：{}", session.getId(), e.getMessage());
        }
    }
}
