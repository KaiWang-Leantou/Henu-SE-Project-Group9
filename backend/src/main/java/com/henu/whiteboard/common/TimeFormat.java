package com.henu.whiteboard.common;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 接口时间格式：统一输出 docs/05 示例里的 +08:00 偏移、秒级 ISO8601。
 */
public final class TimeFormat {

    private static final DateTimeFormatter ISO_OFFSET_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
                    .withZone(ZoneId.of("Asia/Shanghai"));

    private TimeFormat() {
    }

    public static String iso(Instant instant) {
        return instant == null ? null : ISO_OFFSET_SECONDS.format(instant);
    }
}
