package com.henu.whiteboard.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.henu.whiteboard.room.Member;
import com.henu.whiteboard.room.Room;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;

/**
 * WebSocket 出站发送器：负责 JSON 序列化和同一 session 的并发发送保护。
 */
@Component
public class Broadcaster {

    private static final Logger log = LoggerFactory.getLogger(Broadcaster.class);

    private final ObjectMapper objectMapper;

    public Broadcaster(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void send(SessionContext context, JsonNode message) {
        if (context == null || message == null) {
            return;
        }
        try {
            sendText(context, objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            log.error("出站消息序列化失败", e);
        }
    }

    /** 同一房间一次序列化，随后逐个连接发送。 */
    public void broadcast(Room room, JsonNode message) {
        if (room == null || message == null) {
            return;
        }
        final String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("广播消息序列化失败", e);
            return;
        }
        for (SessionContext context : room.sessions()) {
            sendText(context, payload);
        }
    }

    public void broadcastPresence(Room room) {
        if (room == null) {
            return;
        }
        List<Member> members = room.members();
        broadcast(room, Out.presence(members));
    }

    public void sendError(SessionContext context, String cid, String code, String msg) {
        send(context, Out.error(cid, code, msg));
    }

    private void sendText(SessionContext context, String payload) {
        WebSocketSession session = context.webSocketSession();
        if (!session.isOpen()) {
            return;
        }
        // Spring 的 WebSocketSession 不保证写线程安全；按连接串行化发送。
        synchronized (session) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                }
            } catch (IOException | IllegalStateException e) {
                log.warn("发送失败 session={}：{}", session.getId(), e.getMessage());
            }
        }
    }
}
