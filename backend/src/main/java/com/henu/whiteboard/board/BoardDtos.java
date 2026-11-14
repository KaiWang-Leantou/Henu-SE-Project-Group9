package com.henu.whiteboard.board;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 作品库 REST 的请求/响应 DTO；字段名与 docs/05 §2 逐字对齐。
 */
public final class BoardDtos {

    private BoardDtos() {
    }

    public record CreateBoardRequest(String name) {
    }

    /** PATCH：改名与状态流转共用；status 只接受 archived / active。 */
    public record PatchBoardRequest(String name, String status) {
    }

    public record ThumbnailRequest(String dataUrl) {
    }

    /** 新建响应：只包含文档示例里的五个字段。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Created(String id, String name, String role, String status, String createdAt) {
    }

    /** 列表卡片。 */
    public record Item(String id, String name, String role, String status,
                       String thumbnail, String lastSavedAt, String createdAt) {
    }

    public record ListData(long total, int page, int size, List<Item> items) {
    }

    /** 详情；public 是 Java 关键字，用 isPublic + @JsonProperty 映射。 */
    public record Detail(String id, String name, String status,
                         @JsonProperty("public") boolean isPublic, String role,
                         Owner owner, List<MemberView> members,
                         String lastSavedAt, String thumbnail) {
    }

    public record Owner(String id, String nickname) {
    }

    public record MemberView(String id, String nickname, String role) {
    }
}
