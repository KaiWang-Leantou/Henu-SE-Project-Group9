package com.henu.whiteboard.board;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 已定序操作的统一内存表示；既是房间环形缓冲元素，也是重连补发的数据源。
 */
public record OpRecord(long seq, String actor, String ch, JsonNode cmd) {
}
