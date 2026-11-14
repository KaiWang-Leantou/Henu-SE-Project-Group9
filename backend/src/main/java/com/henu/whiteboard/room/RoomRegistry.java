package com.henu.whiteboard.room;

import com.henu.whiteboard.board.BoardDoc;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内房间注册表；每个 boardId 只对应一个 Room，房间空时释放其执行器。
 */
@Component
public class RoomRegistry {

    private final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();

    /** 房间不存在时用已加载的文档创建；已存在时沿用房间内的权威文档与 seq。 */
    public Room getOrCreate(String boardId, BoardDoc doc) {
        return rooms.computeIfAbsent(boardId, id -> new Room(id, doc));
    }

    public Room get(String boardId) {
        return rooms.get(boardId);
    }

    public Collection<Room> all() {
        return rooms.values();
    }

    /** 最后一个连接离开后移除并关闭房间。 */
    public void removeIfEmpty(String boardId, Room room) {
        if (room == null || !room.isEmpty()) {
            return;
        }
        rooms.computeIfPresent(boardId, (id, current) -> {
            if (current == room && current.isEmpty()) {
                current.close();
                return null;
            }
            return current;
        });
    }

    @PreDestroy
    public void shutdown() {
        rooms.values().forEach(Room::close);
        rooms.clear();
    }
}
