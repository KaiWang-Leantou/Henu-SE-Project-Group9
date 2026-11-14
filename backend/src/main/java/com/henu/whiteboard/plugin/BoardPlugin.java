package com.henu.whiteboard.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.henu.whiteboard.room.Member;
import com.henu.whiteboard.room.Room;

import java.util.function.Consumer;

/**
 * 白板插件接缝：游戏等部件通过它观察生命周期、接管通道并参与写权限判定。
 */
public interface BoardPlugin {

    /** 该插件接管的 op 通道，例如 game。 */
    String channel();

    default void onJoin(Room room, Member member) {
    }

    /**
     * 处理该插件通道的消息。reply 用于向发送者单发响应，插件无需感知 WebSocket 细节。
     */
    default void onMessage(Room room, Member member, JsonNode message,
                           Consumer<JsonNode> reply) {
    }

    /** 返回 false 时当前命令会被拒绝；多个插件之间取与（AND）。 */
    default boolean canWrite(Room room, Member member, String cmdType, JsonNode cmd) {
        return true;
    }

    default void onCommitted(Room room, Member member, long seq, String channel, JsonNode cmd) {
    }
}
