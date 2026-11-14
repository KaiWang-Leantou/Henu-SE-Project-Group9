package com.henu.whiteboard.board;

import com.henu.whiteboard.common.ApiCode;
import com.henu.whiteboard.common.ApiException;
import com.henu.whiteboard.persistence.BoardEntity;
import com.henu.whiteboard.persistence.BoardMemberEntity;
import com.henu.whiteboard.persistence.BoardMemberRepository;
import com.henu.whiteboard.persistence.BoardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 白板访问控制：角色判定（owner/editor/viewer）、公开板规则、建板并写 owner 成员行。
 */
@Service
public class BoardAccessService {

    public static final String ROLE_OWNER = "owner";
    public static final String ROLE_EDITOR = "editor";
    public static final String ROLE_VIEWER = "viewer";

    private final BoardRepository boardRepository;
    private final BoardMemberRepository boardMemberRepository;
    private final TransactionTemplate transactionTemplate;
    private final Object createLock = new Object();

    public BoardAccessService(BoardRepository boardRepository,
                              BoardMemberRepository boardMemberRepository,
                              PlatformTransactionManager transactionManager) {
        this.boardRepository = boardRepository;
        this.boardMemberRepository = boardMemberRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Optional<BoardEntity> findBoard(String boardId) {
        if (boardId == null || boardId.isBlank() || boardId.length() > 32) {
            return Optional.empty();
        }
        return boardRepository.findById(boardId);
    }

    public BoardEntity requireBoard(String boardId) {
        return findBoard(boardId)
                .orElseThrow(() -> new ApiException(ApiCode.NOT_FOUND, "白板不存在"));
    }

    /** REST 新建：随机 id，写 wb_board + wb_board_member(owner)。 */
    @Transactional
    public BoardEntity createBoard(String ownerId, String name) {
        return insertBoard(newBoardId(), ownerId, name);
    }

    /**
     * 复制为新板（生命周期 duplicate）：doc_json 里的 boardId 重写为新 id，
     * owner 写当前用户并补成员行；其余（名称/缩略图）由调用方决定。
     */
    @Transactional
    public BoardEntity createBoardCopy(String ownerId, String name, String sourceDocJson,
                                       long sourceDocSeq, String thumbnail) {
        String boardId = newBoardId();
        BoardDoc doc;
        if (sourceDocJson == null || sourceDocJson.isBlank()) {
            doc = BoardDoc.create(boardId);
        } else {
            try {
                doc = BoardDoc.fromJson(sourceDocJson, boardId);
            } catch (IllegalArgumentException e) {
                // 源文档损坏时退回空文档，复制本身不应失败
                doc = BoardDoc.create(boardId);
            }
        }
        doc.setSeq(sourceDocSeq);
        BoardEntity board = insertBoard(boardId, ownerId, name);
        board.setDocJson(doc.toJson());
        board.setDocSeq(sourceDocSeq);
        board.setThumbnail(thumbnail);
        board.setLastSavedAt(Instant.now());
        return boardRepository.save(board);
    }

    private static String newBoardId() {
        return "b_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /**
     * WS 加入时按 boardId 找板，不存在则自动创建（登录用户与游客都允许，归创建者所有，status=DRAFT）。
     * 用 TransactionTemplate + 本地锁保证“查不到再插入”不产生重复主键。
     */
    public BoardEntity findOrCreateForJoin(String boardId, String ownerId) {
        Optional<BoardEntity> existing = findBoard(boardId);
        if (existing.isPresent()) {
            return existing.get();
        }
        synchronized (createLock) {
            return transactionTemplate.execute(status -> boardRepository.findById(boardId)
                    .orElseGet(() -> insertBoard(boardId, ownerId, "白板 " + boardId)));
        }
    }

    private BoardEntity insertBoard(String boardId, String ownerId, String name) {
        Instant now = Instant.now();
        BoardEntity board = new BoardEntity();
        board.setId(boardId);
        board.setName(name);
        board.setOwnerId(ownerId);
        board.setDocSeq(0L);
        // V1.5：新建即"链接可访问、可协作"，与 Excalidraw 的未列出模型一致。
        // 私有/草稿发布留到 Sprint B（届时把 DRAFT 作为未发布态，ACTIVE 才对外）。
        board.setPublic(true);
        board.setStatus(BoardStatus.ACTIVE);
        board.setCreatedAt(now);
        board.setUpdatedAt(now);
        boardRepository.save(board);
        boardMemberRepository.save(new BoardMemberEntity(boardId, ownerId, "OWNER", now));
        return board;
    }

    /**
     * 读权限判定：owner > 成员角色 > 公开 ACTIVE 板（含游客）按 editor。
     * 其余（DRAFT/ARCHIVED 非成员、私有板、TRASHED 非 owner）一律 403。
     */
    public String requireReadRole(BoardEntity board, String userId) {
        String status = BoardStatus.normalize(board.getStatus());
        boolean owner = userId != null && userId.equals(board.getOwnerId());
        if (BoardStatus.TRASHED.equals(status) && !owner) {
            throw forbidden("白板已在回收站，仅创建者可访问");
        }
        if (owner) {
            return ROLE_OWNER;
        }
        String memberRole = memberRole(board.getId(), userId);
        if (memberRole != null) {
            return memberRole;
        }
        if (isPubliclyReadable(board)) {
            return ROLE_EDITOR;
        }
        throw forbidden("无权访问该白板");
    }

    /** 改名/归档/删除等 owner 专属操作。 */
    public String requireOwner(BoardEntity board, String userId) {
        String role = requireReadRole(board, userId);
        if (!ROLE_OWNER.equals(role)) {
            throw forbidden("仅白板创建者可执行该操作");
        }
        return role;
    }

    /** 缩略图等 editor+ 操作。 */
    public String requireEditor(BoardEntity board, String userId) {
        String role = requireReadRole(board, userId);
        if (!ROLE_OWNER.equals(role) && !ROLE_EDITOR.equals(role)) {
            throw forbidden("需要编辑权限");
        }
        return role;
    }

    /** 公开可读 = is_public 且 ACTIVE；DRAFT/ARCHIVED/TRASHED 一律非公开。 */
    public boolean isPubliclyReadable(BoardEntity board) {
        return board.isPublic() && BoardStatus.ACTIVE.equals(BoardStatus.normalize(board.getStatus()));
    }

    /** 归档 / 回收站板是只读的：REST 改名、缩略图、存档版本一律 403（docs/07 §2）。 */
    public boolean isReadOnly(BoardEntity board) {
        String status = BoardStatus.normalize(board.getStatus());
        return BoardStatus.ARCHIVED.equals(status) || BoardStatus.TRASHED.equals(status);
    }

    public void requireWritable(BoardEntity board) {
        if (isReadOnly(board)) {
            throw forbidden("白板已归档或在回收站，只读");
        }
    }

    public String memberRole(String boardId, String userId) {
        if (userId == null) {
            return null;
        }
        return boardMemberRepository.findByBoardIdAndUserId(boardId, userId)
                .map(BoardMemberEntity::getRole)
                .map(role -> role.toLowerCase(Locale.ROOT))
                .orElse(null);
    }

    private static ApiException forbidden(String message) {
        return new ApiException(ApiCode.FORBIDDEN, message);
    }
}
