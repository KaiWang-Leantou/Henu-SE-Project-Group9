package com.henu.whiteboard.board;

import com.henu.whiteboard.auth.CurrentUser;
import com.henu.whiteboard.auth.UserContext;
import com.henu.whiteboard.common.ApiCode;
import com.henu.whiteboard.common.ApiException;
import com.henu.whiteboard.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.henu.whiteboard.board.BoardDtos.CreateBoardRequest;
import static com.henu.whiteboard.board.BoardDtos.Created;
import static com.henu.whiteboard.board.BoardDtos.Detail;
import static com.henu.whiteboard.board.BoardDtos.ListData;
import static com.henu.whiteboard.board.BoardDtos.PatchBoardRequest;
import static com.henu.whiteboard.board.BoardDtos.ThumbnailRequest;

/**
 * 作品库 REST 入口：列表、新建、详情、改名/归档、回收站、复制与缩略图。
 */
@RestController
@RequestMapping("/api/boards")
public class BoardController {

    private final BoardMetaService boardMetaService;
    private final BoardLifecycleService boardLifecycleService;

    public BoardController(BoardMetaService boardMetaService,
                           BoardLifecycleService boardLifecycleService) {
        this.boardMetaService = boardMetaService;
        this.boardLifecycleService = boardLifecycleService;
    }

    @GetMapping
    public ApiResponse<ListData> list(@RequestParam(defaultValue = "mine") String scope,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        CurrentUser user = UserContext.require();
        return ApiResponse.ok(boardMetaService.list(user, scope, status, q, page, size));
    }

    @PostMapping
    public ApiResponse<Created> create(@RequestBody(required = false) CreateBoardRequest request) {
        CurrentUser user = UserContext.require();
        return ApiResponse.ok(boardMetaService.create(user, request == null ? null : request.name()));
    }

    @GetMapping("/{boardId}")
    public ApiResponse<Detail> detail(@PathVariable String boardId) {
        return ApiResponse.ok(boardMetaService.get(UserContext.require(), boardId));
    }

    /**
     * 改名 / 归档 / 取消归档：{name?} 或 {status:"archived"|"active"}，OWNER 专属。
     * 归档板上的改名会被 requireWritable 拦成 403。
     */
    @PatchMapping("/{boardId}")
    public ApiResponse<Void> patch(@PathVariable String boardId,
                                   @RequestBody(required = false) PatchBoardRequest request) {
        CurrentUser user = UserContext.require();
        boolean hasName = request != null && request.name() != null;
        boolean hasStatus = request != null && request.status() != null;
        if (!hasName && !hasStatus) {
            throw new ApiException(ApiCode.VALIDATION_ERROR, "name 与 status 至少提供一个");
        }
        if (hasName) {
            boardMetaService.rename(user, boardId, request.name());
        }
        if (hasStatus) {
            boardLifecycleService.updateStatus(user, boardId, request.status());
        }
        return ApiResponse.ok();
    }

    @PutMapping("/{boardId}/thumbnail")
    public ApiResponse<Void> updateThumbnail(@PathVariable String boardId,
                                             @RequestBody(required = false) ThumbnailRequest request) {
        boardMetaService.updateThumbnail(UserContext.require(), boardId,
                request == null ? null : request.dataUrl());
        return ApiResponse.ok();
    }

    /** 软删除进回收站（TRASHED + trashed_at），OWNER 专属。 */
    @DeleteMapping("/{boardId}")
    public ApiResponse<Void> trash(@PathVariable String boardId) {
        boardLifecycleService.trash(UserContext.require(), boardId);
        return ApiResponse.ok();
    }

    /** 回收站恢复：TRASHED → ACTIVE，OWNER 专属。 */
    @PostMapping("/{boardId}/trash/restore")
    public ApiResponse<Void> restoreFromTrash(@PathVariable String boardId) {
        boardLifecycleService.restoreFromTrash(UserContext.require(), boardId);
        return ApiResponse.ok();
    }

    /** 复制为新板（新 id、owner=当前用户、ACTIVE），用于“从归档作品继续联机”。 */
    @PostMapping("/{boardId}/duplicate")
    public ApiResponse<Created> duplicate(@PathVariable String boardId) {
        return ApiResponse.ok(boardLifecycleService.duplicate(UserContext.require(), boardId));
    }
}
