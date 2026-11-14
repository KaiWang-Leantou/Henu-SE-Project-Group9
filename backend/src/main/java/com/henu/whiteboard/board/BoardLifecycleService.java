package com.henu.whiteboard.board;

import com.henu.whiteboard.auth.CurrentUser;
import com.henu.whiteboard.common.ApiCode;
import com.henu.whiteboard.common.ApiException;
import com.henu.whiteboard.common.TimeFormat;
import com.henu.whiteboard.persistence.BoardEntity;
import com.henu.whiteboard.persistence.BoardRepository;
import com.henu.whiteboard.room.Room;
import com.henu.whiteboard.room.RoomRegistry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;

/**
 * 白板生命周期：归档 / 取消归档 / 回收站 / 回收站恢复 / 复制为新板。
 * 状态变更后同步房间只读标记并广播 board.reload。
 */
@Service
public class BoardLifecycleService {

    private final BoardRepository boardRepository;
    private final BoardAccessService boardAccessService;
    private final BoardEvents boardEvents;
    private final RoomRegistry roomRegistry;

    public BoardLifecycleService(BoardRepository boardRepository,
                                 BoardAccessService boardAccessService,
                                 BoardEvents boardEvents,
                                 RoomRegistry roomRegistry) {
        this.boardRepository = boardRepository;
        this.boardAccessService = boardAccessService;
        this.boardEvents = boardEvents;
        this.roomRegistry = roomRegistry;
    }

    /** PATCH status：只接受 archived / active，OWNER 专属；回收站中的板须走恢复接口。 */
    public void updateStatus(CurrentUser user, String boardId, String rawStatus) {
        String status = rawStatus == null ? "" : rawStatus.trim().toLowerCase(Locale.ROOT);
        if (!"archived".equals(status) && !"active".equals(status)) {
            throw new ApiException(ApiCode.VALIDATION_ERROR, "status 仅支持 archived/active");
        }
        BoardEntity board = boardAccessService.requireBoard(boardId);
        boardAccessService.requireOwner(board, user.id());
        String current = BoardStatus.normalize(board.getStatus());
        if (BoardStatus.TRASHED.equals(current)) {
            throw new ApiException(ApiCode.FORBIDDEN, "回收站中的白板请先恢复");
        }

        if ("archived".equals(status)) {
            if (BoardStatus.ARCHIVED.equals(current)) {
                return;
            }
            board.setStatus(BoardStatus.ARCHIVED);
            board.setArchivedAt(Instant.now());
            boardRepository.save(board);
            updateRoomStatus(boardId, BoardStatus.ARCHIVED);
            boardEvents.reload(boardId, "archived");
            return;
        }

        if (BoardStatus.ACTIVE.equals(current)) {
            return;
        }
        board.setStatus(BoardStatus.ACTIVE);
        board.setArchivedAt(null);
        boardRepository.save(board);
        updateRoomStatus(boardId, BoardStatus.ACTIVE);
        boardEvents.reload(boardId, "active");
    }

    /** DELETE：软删除进回收站，OWNER 专属。 */
    public void trash(CurrentUser user, String boardId) {
        BoardEntity board = boardAccessService.requireBoard(boardId);
        boardAccessService.requireOwner(board, user.id());
        if (BoardStatus.TRASHED.equals(BoardStatus.normalize(board.getStatus()))) {
            return;
        }
        board.setStatus(BoardStatus.TRASHED);
        board.setTrashedAt(Instant.now());
        boardRepository.save(board);
        updateRoomStatus(boardId, BoardStatus.TRASHED);
        boardEvents.reload(boardId, "trashed");
    }

    /** 回收站恢复：TRASHED → ACTIVE，OWNER 专属。 */
    public void restoreFromTrash(CurrentUser user, String boardId) {
        BoardEntity board = boardAccessService.requireBoard(boardId);
        boardAccessService.requireOwner(board, user.id());
        if (!BoardStatus.TRASHED.equals(BoardStatus.normalize(board.getStatus()))) {
            throw new ApiException(ApiCode.VALIDATION_ERROR, "白板不在回收站");
        }
        board.setStatus(BoardStatus.ACTIVE);
        board.setTrashedAt(null);
        board.setArchivedAt(null);
        boardRepository.save(board);
        updateRoomStatus(boardId, BoardStatus.ACTIVE);
        boardEvents.reload(boardId, "restored");
    }

    /** 复制为新板：doc_json 复制并重写 boardId，owner=当前用户，状态 ACTIVE。 */
    public BoardDtos.Created duplicate(CurrentUser user, String boardId) {
        BoardEntity source = boardAccessService.requireBoard(boardId);
        boardAccessService.requireReadRole(source, user.id());
        String name = duplicateName(source.getName());
        BoardEntity created = boardAccessService.createBoardCopy(user.id(), name,
                source.getDocJson(), source.getDocSeq(), source.getThumbnail());
        return new BoardDtos.Created(created.getId(), created.getName(),
                BoardAccessService.ROLE_OWNER, BoardStatus.lower(created.getStatus()),
                TimeFormat.iso(created.getCreatedAt()));
    }

    private void updateRoomStatus(String boardId, String status) {
        Room room = roomRegistry.get(boardId);
        if (room != null && !room.isClosed()) {
            room.setStatus(status);
        }
    }

    private static String duplicateName(String sourceName) {
        String base = sourceName == null || sourceName.isBlank() ? "白板" : sourceName.trim();
        String name = base + " 副本";
        return name.length() > 100 ? name.substring(0, 100) : name;
    }
}
