package com.henu.whiteboard.board;

import com.henu.whiteboard.persistence.BoardEntity;
import com.henu.whiteboard.persistence.BoardRepository;
import com.henu.whiteboard.room.Member;
import com.henu.whiteboard.room.Room;
import com.henu.whiteboard.room.RoomRegistry;
import com.henu.whiteboard.ws.Broadcaster;
import com.henu.whiteboard.ws.Out;
import com.henu.whiteboard.ws.SessionContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 房间事件广播：归档/回收站/回滚后通知在线客户端。
 * board.reload 用于让客户端丢弃未 ack 命令；init 用于回滚后的全量重同步。
 */
@Component
public class BoardEvents {

    private final RoomRegistry roomRegistry;
    private final Broadcaster broadcaster;
    private final BoardRepository boardRepository;

    public BoardEvents(RoomRegistry roomRegistry, Broadcaster broadcaster,
                       BoardRepository boardRepository) {
        this.roomRegistry = roomRegistry;
        this.broadcaster = broadcaster;
        this.boardRepository = boardRepository;
    }

    /** 只广播 board.reload{seq, reason}；房间不存在时静默跳过。 */
    public void reload(String boardId, String reason) {
        Room room = roomRegistry.get(boardId);
        if (room == null || room.isClosed()) {
            return;
        }
        broadcaster.broadcast(room, Out.reload(room.seq(), reason));
    }

    /** 先 board.reload 再逐连接发 init（回滚用）。 */
    public void reloadAndInit(String boardId, String reason) {
        Room room = roomRegistry.get(boardId);
        if (room == null || room.isClosed()) {
            return;
        }
        broadcaster.broadcast(room, Out.reload(room.seq(), reason));
        broadcastInit(room);
    }

    private void broadcastInit(Room room) {
        Instant savedAt = boardRepository.findById(room.boardId())
                .map(BoardEntity::getLastSavedAt)
                .orElse(null);
        BoardDoc.NodeSnapshot snapshot = room.doc().nodeSnapshot();
        List<Member> members = room.members();
        for (SessionContext context : room.sessions()) {
            if (!context.isJoined()) {
                continue;
            }
            broadcaster.send(context, Out.init(room.boardId(), snapshot.seq(), snapshot.doc(),
                    context.toMember(), members, savedAt));
        }
    }
}
