package com.henu.whiteboard.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.henu.whiteboard.common.TimeFormat;
import com.henu.whiteboard.room.Member;

import java.time.Instant;
import java.util.List;

/**
 * 出站消息构造工具；字段名严格对应 docs/02，不额外添加协议字段。
 */
public final class Out {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Out() {
    }

    public static ObjectNode pong() {
        return NODES.objectNode().put("t", "pong");
    }

    public static ObjectNode error(String cid, String code, String msg) {
        ObjectNode node = NODES.objectNode();
        node.put("t", "error");
        if (cid != null && !cid.isBlank()) {
            node.put("cid", cid);
        }
        node.put("code", code);
        node.put("msg", msg == null ? "" : msg);
        return node;
    }

    public static ObjectNode init(String boardId, long seq, JsonNode doc,
                                  Member me, List<Member> members, Instant savedAt) {
        ObjectNode node = NODES.objectNode();
        node.put("t", "init");
        node.put("boardId", boardId);
        node.put("seq", seq);
        node.set("doc", doc);
        node.set("me", member(me));
        node.set("members", members(members));
        node.put("savedAt", TimeFormat.iso(savedAt));
        return node;
    }

    /** 快照落库后广播：客户端据此显示“已自动保存 hh:mm:ss”。 */
    public static ObjectNode saved(long seq, Instant at) {
        ObjectNode node = NODES.objectNode();
        node.put("t", "saved");
        node.put("seq", seq);
        node.put("at", TimeFormat.iso(at));
        return node;
    }

    public static ObjectNode op(long seq, String actor, String ch, JsonNode cmd) {
        ObjectNode node = NODES.objectNode();
        node.put("t", "op");
        node.put("seq", seq);
        node.put("actor", actor);
        node.put("ch", ch);
        node.set("cmd", cmd);
        return node;
    }

    public static ObjectNode ack(String cid, long seq) {
        ObjectNode node = NODES.objectNode();
        node.put("t", "ack");
        node.put("cid", cid);
        node.put("seq", seq);
        return node;
    }

    public static ObjectNode strokeAppend(String actor, String pageId, String strokeId,
                                          String tool, String color, double width,
                                          JsonNode pts) {
        ObjectNode node = NODES.objectNode();
        node.put("t", "stroke.append");
        node.put("actor", actor);
        node.put("pageId", pageId);
        node.put("strokeId", strokeId);
        node.put("tool", tool);
        node.put("color", color);
        node.put("width", width);
        node.set("pts", pts);
        return node;
    }

    public static ObjectNode strokeEnd(String actor, String strokeId, long seq) {
        ObjectNode node = NODES.objectNode();
        node.put("t", "stroke.end");
        node.put("actor", actor);
        node.put("strokeId", strokeId);
        node.put("seq", seq);
        return node;
    }

    public static ObjectNode cursor(String actor, double x, double y) {
        ObjectNode node = NODES.objectNode();
        node.put("t", "cursor");
        node.put("actor", actor);
        node.put("x", x);
        node.put("y", y);
        return node;
    }

    /** 回滚/归档等强制重同步信号；客户端收到后丢弃未 ack 命令并重新拉取。 */
    public static ObjectNode reload(long seq, String reason) {
        ObjectNode node = NODES.objectNode();
        node.put("t", "board.reload");
        node.put("seq", seq);
        node.put("reason", reason == null ? "" : reason);
        return node;
    }

    public static ObjectNode presence(List<Member> members) {
        ObjectNode node = NODES.objectNode();
        node.put("t", "presence");
        node.set("members", members(members));
        return node;
    }

    private static ObjectNode member(Member member) {
        if (member == null) {
            return NODES.objectNode();
        }
        ObjectNode node = NODES.objectNode();
        node.put("id", member.id());
        node.put("name", member.name());
        node.put("role", member.role());
        node.put("online", member.online());
        node.put("guest", member.guest());
        node.put("color", member.color());
        return node;
    }

    private static ArrayNode members(List<Member> members) {
        ArrayNode array = NODES.arrayNode();
        if (members != null) {
            for (Member member : members) {
                array.add(member(member));
            }
        }
        return array;
    }
}
