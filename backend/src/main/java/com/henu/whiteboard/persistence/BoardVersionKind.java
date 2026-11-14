package com.henu.whiteboard.persistence;

/**
 * 版本类型常量（docs/07 §1.2）：AUTO 自动 / MANUAL 手动 / PRE_RESTORE 回滚前备份。
 */
public final class BoardVersionKind {

    public static final String AUTO = "AUTO";
    public static final String MANUAL = "MANUAL";
    public static final String PRE_RESTORE = "PRE_RESTORE";

    private BoardVersionKind() {
    }
}
