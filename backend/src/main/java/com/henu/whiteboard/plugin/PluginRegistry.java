package com.henu.whiteboard.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.henu.whiteboard.room.Member;
import com.henu.whiteboard.room.Room;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 插件注册表：按 channel 分发消息，并把所有插件的 canWrite 做 AND 汇总。
 */
@Component
public class PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);

    private final List<BoardPlugin> plugins;

    public PluginRegistry(List<BoardPlugin> plugins) {
        this.plugins = List.copyOf(plugins);
    }

    public Optional<BoardPlugin> byChannel(String channel) {
        return plugins.stream()
                .filter(plugin -> Objects.equals(plugin.channel(), channel))
                .findFirst();
    }

    public boolean hasChannel(String channel) {
        return byChannel(channel).isPresent();
    }

    /**
     * 所有插件依次判定，任一为 false 即拒绝；异常按拒绝处理，保证白板核心不被插件拖垮。
     */
    public boolean canWrite(Room room, Member member, String cmdType, JsonNode cmd) {
        for (BoardPlugin plugin : plugins) {
            try {
                if (!plugin.canWrite(room, member, cmdType, cmd)) {
                    return false;
                }
            } catch (RuntimeException e) {
                log.warn("插件 {} 的 canWrite 异常，按拒绝处理", plugin.channel(), e);
                return false;
            }
        }
        return true;
    }

    public void fireJoin(Room room, Member member) {
        for (BoardPlugin plugin : plugins) {
            try {
                plugin.onJoin(room, member);
            } catch (RuntimeException e) {
                log.warn("插件 {} 的 onJoin 异常", plugin.channel(), e);
            }
        }
    }

    public void fireCommitted(Room room, Member member, long seq, String channel, JsonNode cmd) {
        for (BoardPlugin plugin : plugins) {
            try {
                plugin.onCommitted(room, member, seq, channel, cmd);
            } catch (RuntimeException e) {
                log.warn("插件 {} 的 onCommitted 异常", plugin.channel(), e);
            }
        }
    }

    /** 返回 false 表示没有插件接管该 channel。 */
    public boolean dispatch(String channel, Room room, Member member,
                            JsonNode message, Consumer<JsonNode> reply) {
        Optional<BoardPlugin> plugin = byChannel(channel);
        if (plugin.isEmpty()) {
            return false;
        }
        plugin.get().onMessage(room, member, message, reply);
        return true;
    }
}
