package com.henu.whiteboard.board;

import java.util.Locale;
import java.util.Set;

/**
 * 白板生命周期状态常量与大小写归一；数据库存大写，REST 输出小写。
 */
public final class BoardStatus {

    public static final String DRAFT = "DRAFT";
    public static final String ACTIVE = "ACTIVE";
    public static final String ARCHIVED = "ARCHIVED";
    public static final String TRASHED = "TRASHED";

    private static final Set<String> ALL = Set.of(DRAFT, ACTIVE, ARCHIVED, TRASHED);

    private BoardStatus() {
    }

    /** 非法状态返回 null，由调用方决定报错还是取默认值。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return ALL.contains(upper) ? upper : null;
    }

    public static String lower(String status) {
        return status == null ? null : status.toLowerCase(Locale.ROOT);
    }
}
