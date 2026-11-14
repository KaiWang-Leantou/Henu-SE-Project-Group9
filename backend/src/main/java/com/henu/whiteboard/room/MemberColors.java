package com.henu.whiteboard.room;

/**
 * 远端光标调色板（docs/07 §3）：按 userId 哈希稳定取色，同一用户每次进房颜色不变。
 */
public final class MemberColors {

    /** 12 色板；顺序即前端设置面板展示顺序。 */
    private static final String[] PALETTE = {
            "#E5484D", "#E93D82", "#8E4EC6", "#6E56CF", "#3E63DD", "#0091FF",
            "#12A594", "#30A46C", "#FFB224", "#F76B15", "#8D8D8D", "#0A0A0A"
    };

    private MemberColors() {
    }

    /**
     * String.hashCode 在 Java 规范里是稳定的（同一字符串跨进程一致），
     * 配合 floorMod 避免负数下标，即可保证“哈希稳定取色”。
     */
    public static String of(String userId) {
        if (userId == null || userId.isBlank()) {
            return PALETTE[0];
        }
        return PALETTE[Math.floorMod(userId.hashCode(), PALETTE.length)];
    }

    /** 供测试/前端契约核对使用。 */
    public static String[] palette() {
        return PALETTE.clone();
    }
}
