package com.henu.whiteboard.board;

import java.util.List;

/**
 * 版本历史 REST 的请求/响应 DTO；字段对齐 docs/07 §1.3。
 */
public final class BoardVersionDtos {

    private BoardVersionDtos() {
    }

    /** 手动存档请求：label / thumbnail 都可缺省。 */
    public record CreateVersionRequest(String label, String thumbnail) {
    }

    /** 时间轴条目；author 为昵称，系统自动版本为 null。 */
    public record VersionView(String id, int versionNo, String kind, String label, long docSeq,
                              String author, String createdAt, String thumbnail) {
    }

    public record VersionListData(long total, int page, int size, List<VersionView> items) {
    }

    /** 回滚结果：客户端可据此提示“已回到 v3”。 */
    public record RestoreResult(String versionId, int versionNo, long seq) {
    }
}
