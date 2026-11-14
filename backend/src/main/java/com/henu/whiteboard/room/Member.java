package com.henu.whiteboard.room;

/**
 * 房间成员快照；字段与协议里的 me / members[] 对应，guest 仅用于区分游客身份。
 * color 由 userId 哈希从 12 色板稳定分配（docs/07 §3），供前端绘制远端光标。
 */
public record Member(String id, String name, String role, boolean online, boolean guest, String color) {

    public Member {
        if (color == null || color.isBlank()) {
            color = MemberColors.of(id);
        }
    }

    public boolean isOwner() {
        return "owner".equals(role);
    }

    public boolean canEdit() {
        return isOwner() || "editor".equals(role);
    }
}
