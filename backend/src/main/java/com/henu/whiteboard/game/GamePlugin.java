package com.henu.whiteboard.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.henu.whiteboard.plugin.BoardPlugin;
import com.henu.whiteboard.room.Member;
import com.henu.whiteboard.room.Room;
import com.henu.whiteboard.ws.Out;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 游戏通道 V1 空实现：只占住 game channel，所有消息统一回 NOT_IMPLEMENTED。
 */
// TODO(V2): 状态机见 docs/03
@Component
public class GamePlugin implements BoardPlugin {

    @Override
    public String channel() {
        return "game";
    }

    @Override
    public void onMessage(Room room, Member member, JsonNode message,
                          Consumer<JsonNode> reply) {
        String cid = message.path("cid").isTextual() ? message.path("cid").asText() : null;
        reply.accept(Out.error(cid, "NOT_IMPLEMENTED", "游戏功能将在 V2 实现"));
    }

    @Override
    public boolean canWrite(Room room, Member member, String cmdType, JsonNode cmd) {
        return true;
    }
}
