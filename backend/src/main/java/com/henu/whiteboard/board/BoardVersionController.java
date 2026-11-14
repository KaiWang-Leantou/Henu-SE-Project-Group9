package com.henu.whiteboard.board;

import com.henu.whiteboard.auth.CurrentUser;
import com.henu.whiteboard.auth.UserContext;
import com.henu.whiteboard.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 版本历史 REST：时间轴 / 手动存档 / 回滚（docs/07 §1.3）。
 */
@RestController
@RequestMapping("/api/boards/{boardId}/versions")
public class BoardVersionController {

    private final BoardVersionService boardVersionService;

    public BoardVersionController(BoardVersionService boardVersionService) {
        this.boardVersionService = boardVersionService;
    }

    @GetMapping
    public ApiResponse<BoardVersionDtos.VersionListData> list(
            @PathVariable String boardId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        CurrentUser user = UserContext.require();
        return ApiResponse.ok(boardVersionService.list(user, boardId, page, size));
    }

    @PostMapping
    public ApiResponse<BoardVersionDtos.VersionView> create(
            @PathVariable String boardId,
            @RequestBody(required = false) BoardVersionDtos.CreateVersionRequest request) {
        CurrentUser user = UserContext.require();
        return ApiResponse.ok(boardVersionService.createManual(user, boardId, request));
    }

    @PostMapping("/{versionId}/restore")
    public ApiResponse<BoardVersionDtos.RestoreResult> restore(
            @PathVariable String boardId,
            @PathVariable String versionId) {
        CurrentUser user = UserContext.require();
        return ApiResponse.ok(boardVersionService.restore(user, boardId, versionId));
    }
}
