package com.henu.whiteboard.board;

import com.henu.whiteboard.auth.CurrentUser;
import com.henu.whiteboard.common.ApiCode;
import com.henu.whiteboard.common.ApiException;
import com.henu.whiteboard.common.TimeFormat;
import com.henu.whiteboard.persistence.BoardEntity;
import com.henu.whiteboard.persistence.BoardMemberEntity;
import com.henu.whiteboard.persistence.BoardMemberRepository;
import com.henu.whiteboard.persistence.BoardRepository;
import com.henu.whiteboard.persistence.UserEntity;
import com.henu.whiteboard.persistence.UserRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 作品库元数据服务：列表筛选/分页、新建、详情、改名与缩略图上传。
 */
@Service
public class BoardMetaService {

    private static final Set<String> SCOPES = Set.of("mine", "joined", "all");
    private static final Set<String> LIST_STATUSES = Set.of("active", "draft", "archived", "trashed");
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_QUERY_LENGTH = 100;
    private static final int MAX_THUMBNAIL_BYTES = 256 * 1024;
    private static final String PNG_PREFIX = "data:image/png;base64,";
    private static final String WEBP_PREFIX = "data:image/webp;base64,";

    private final BoardRepository boardRepository;
    private final BoardMemberRepository boardMemberRepository;
    private final UserRepository userRepository;
    private final BoardAccessService boardAccessService;

    public BoardMetaService(BoardRepository boardRepository,
                            BoardMemberRepository boardMemberRepository,
                            UserRepository userRepository,
                            BoardAccessService boardAccessService) {
        this.boardRepository = boardRepository;
        this.boardMemberRepository = boardMemberRepository;
        this.userRepository = userRepository;
        this.boardAccessService = boardAccessService;
    }

    @Transactional(readOnly = true)
    public BoardDtos.ListData list(CurrentUser user, String rawScope, String rawStatus,
                                   String rawQuery, int page, int size) {
        String scope = normalizeScope(rawScope);
        String status = normalizeStatus(rawStatus);
        if (page < 1) {
            throw validation("page 必须从 1 开始");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw validation("size 范围需为 1–50");
        }
        String keyword = rawQuery == null ? "" : rawQuery.trim();
        if (keyword.length() > MAX_QUERY_LENGTH) {
            throw validation("q 最长 100 个字符");
        }

        Page<BoardEntity> pageResult = boardRepository.findAll(
                buildSpecification(user, scope, status, keyword),
                PageRequest.of(page - 1, size));

        Map<String, String> memberRoles = boardMemberRepository.findByUserId(user.id()).stream()
                .collect(Collectors.toMap(BoardMemberEntity::getBoardId,
                        row -> row.getRole().toLowerCase(Locale.ROOT), (left, right) -> left));
        List<BoardDtos.Item> items = pageResult.getContent().stream()
                .map(board -> toItem(board, roleForList(board, user, memberRoles)))
                .toList();
        return new BoardDtos.ListData(pageResult.getTotalElements(), page, size, items);
    }

    @Transactional
    public BoardDtos.Created create(CurrentUser user, String rawName) {
        if (user.guest()) {
            throw new ApiException(ApiCode.FORBIDDEN, "登录后才能创建作品");
        }
        String name = requireBoardName(rawName);
        BoardEntity board = boardAccessService.createBoard(user.id(), name);
        return new BoardDtos.Created(board.getId(), board.getName(), BoardAccessService.ROLE_OWNER,
                BoardStatus.lower(board.getStatus()), TimeFormat.iso(board.getCreatedAt()));
    }

    @Transactional(readOnly = true)
    public BoardDtos.Detail get(CurrentUser user, String boardId) {
        BoardEntity board = boardAccessService.requireBoard(boardId);
        String role = boardAccessService.requireReadRole(board, user.id());

        List<BoardMemberEntity> rows = boardMemberRepository.findByBoardIdOrderByJoinedAtAsc(boardId);
        Set<String> userIds = new LinkedHashSet<>();
        userIds.add(board.getOwnerId());
        rows.forEach(row -> userIds.add(row.getUserId()));
        Map<String, UserEntity> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));

        List<BoardDtos.MemberView> members = new ArrayList<>();
        members.add(new BoardDtos.MemberView(board.getOwnerId(),
                nicknameOf(users, board.getOwnerId()), BoardAccessService.ROLE_OWNER));
        for (BoardMemberEntity row : rows) {
            if (board.getOwnerId().equals(row.getUserId())) {
                continue;
            }
            members.add(new BoardDtos.MemberView(row.getUserId(), nicknameOf(users, row.getUserId()),
                    row.getRole().toLowerCase(Locale.ROOT)));
        }

        return new BoardDtos.Detail(
                board.getId(),
                board.getName(),
                BoardStatus.lower(board.getStatus()),
                boardAccessService.isPubliclyReadable(board),
                role,
                new BoardDtos.Owner(board.getOwnerId(), nicknameOf(users, board.getOwnerId())),
                members,
                TimeFormat.iso(board.getLastSavedAt()),
                board.getThumbnail());
    }

    @Transactional
    public void rename(CurrentUser user, String boardId, String rawName) {
        if (user.guest()) {
            throw new ApiException(ApiCode.FORBIDDEN, "游客不能修改作品，请先登录");
        }
        BoardEntity board = boardAccessService.requireBoard(boardId);
        boardAccessService.requireOwner(board, user.id());
        boardAccessService.requireWritable(board);
        board.setName(requireBoardName(rawName));
        boardRepository.save(board);
    }

    @Transactional
    public void updateThumbnail(CurrentUser user, String boardId, String dataUrl) {
        if (user.guest()) {
            throw new ApiException(ApiCode.FORBIDDEN, "游客不能上传缩略图，请先登录");
        }
        BoardEntity board = boardAccessService.requireBoard(boardId);
        boardAccessService.requireEditor(board, user.id());
        boardAccessService.requireWritable(board);
        board.setThumbnail(validateThumbnail(dataUrl));
        boardRepository.save(board);
    }

    private Specification<BoardEntity> buildSpecification(CurrentUser user, String scope,
                                                          String status, String keyword) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            switch (scope) {
                case "mine" -> predicates.add(cb.equal(root.get("ownerId"), user.id()));
                case "joined" -> {
                    List<String> boardIds = boardMemberRepository.findByUserId(user.id()).stream()
                            .filter(row -> !"OWNER".equalsIgnoreCase(row.getRole()))
                            .map(BoardMemberEntity::getBoardId)
                            .toList();
                    if (boardIds.isEmpty()) {
                        predicates.add(cb.disjunction());
                    } else {
                        predicates.add(root.get("id").in(boardIds));
                    }
                }
                case "all" -> {
                    predicates.add(cb.equal(root.get("isPublic"), true));
                    predicates.add(cb.equal(root.get("status"), BoardStatus.ACTIVE));
                }
                default -> throw validation("scope 仅支持 mine/joined/all");
            }
            if (!"all".equals(scope)) {
                if (status != null) {
                    predicates.add(cb.equal(root.get("status"), status.toUpperCase(Locale.ROOT)));
                } else {
                    // 未显式指定 status 时返回除回收站外的全部作品（含刚创建的 DRAFT）
                    predicates.add(cb.notEqual(root.get("status"), BoardStatus.TRASHED));
                }
            }
            if (!keyword.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("name")),
                        "%" + keyword.toLowerCase(Locale.ROOT) + "%"));
            }
            // 固定排序：最近保存优先，从未保存的按创建时间兜底（H2/MySQL 的 NULL 排序不同，用 coalesce 抹平）
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                query.orderBy(cb.desc(cb.coalesce(root.get("lastSavedAt"), root.get("createdAt"))),
                        cb.desc(root.get("createdAt")));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private String roleForList(BoardEntity board, CurrentUser user, Map<String, String> memberRoles) {
        if (user.id().equals(board.getOwnerId())) {
            return BoardAccessService.ROLE_OWNER;
        }
        String memberRole = memberRoles.get(board.getId());
        if (memberRole != null) {
            return memberRole;
        }
        return boardAccessService.isPubliclyReadable(board)
                ? BoardAccessService.ROLE_EDITOR : BoardAccessService.ROLE_VIEWER;
    }

    private static BoardDtos.Item toItem(BoardEntity board, String role) {
        return new BoardDtos.Item(board.getId(), board.getName(), role,
                BoardStatus.lower(board.getStatus()), board.getThumbnail(),
                TimeFormat.iso(board.getLastSavedAt()), TimeFormat.iso(board.getCreatedAt()));
    }

    private static String nicknameOf(Map<String, UserEntity> users, String userId) {
        UserEntity user = users.get(userId);
        return user == null ? userId : user.getNickname();
    }

    private static String normalizeScope(String raw) {
        String scope = raw == null || raw.isBlank() ? "mine" : raw.trim().toLowerCase(Locale.ROOT);
        if (!SCOPES.contains(scope)) {
            throw validation("scope 仅支持 mine/joined/all");
        }
        return scope;
    }

    /**
     * status 缺省返回 null：作品库默认展示“我的全部（不含回收站）”，
     * 显式传 active/draft/archived 时才按状态过滤。
     */
    private static String normalizeStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String status = raw.trim().toLowerCase(Locale.ROOT);
        if (!LIST_STATUSES.contains(status)) {
            throw validation("status 仅支持 active/draft/archived/trashed");
        }
        return status;
    }

    private static String requireBoardName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw validation("白板名称长度需为 1–100 个字符");
        }
        return name;
    }

    /**
     * 校验 data URL：仅 webp/png、base64 可解码、解码后 ≤256KB，并核对文件头避免伪装。
     * 版本缩略图复用同一套校验（同包调用）。
     */
    static String validateThumbnail(String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) {
            throw validation("缩略图不能为空");
        }
        boolean webp;
        String base64;
        if (dataUrl.startsWith(PNG_PREFIX)) {
            webp = false;
            base64 = dataUrl.substring(PNG_PREFIX.length());
        } else if (dataUrl.startsWith(WEBP_PREFIX)) {
            webp = true;
            base64 = dataUrl.substring(WEBP_PREFIX.length());
        } else {
            throw validation("缩略图仅支持 image/png 或 image/webp 的 data URL");
        }

        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw validation("缩略图 base64 内容非法");
        }
        if (bytes.length == 0 || bytes.length > MAX_THUMBNAIL_BYTES) {
            throw validation("缩略图解码后不能超过 256KB");
        }
        if (webp ? !isWebp(bytes) : !isPng(bytes)) {
            throw validation("缩略图内容与声明的图片类型不一致");
        }
        return dataUrl;
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A;
    }

    private static boolean isWebp(byte[] bytes) {
        return bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    private static ApiException validation(String message) {
        return new ApiException(ApiCode.VALIDATION_ERROR, message);
    }
}
