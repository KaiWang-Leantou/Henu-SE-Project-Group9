package com.henu.whiteboard.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 白板版本实体，对应 wb_board_version 表；一行是一次完整文档快照。
 * kind 取 AUTO / MANUAL / PRE_RESTORE，落库统一大写。
 */
@Entity
@Table(name = "wb_board_version")
public class BoardVersionEntity {

    @Id
    @Column(name = "id", length = 32, nullable = false)
    private String id;

    @Column(name = "board_id", length = 32, nullable = false)
    private String boardId;

    /** 单板内单调递增的版本号，从 1 开始。 */
    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "kind", length = 16, nullable = false)
    private String kind;

    @Column(name = "label", length = 100)
    private String label;

    @Column(name = "doc_json", columnDefinition = "MEDIUMTEXT", nullable = false)
    private String docJson;

    @Column(name = "doc_seq", nullable = false)
    private long docSeq;

    @Column(name = "thumbnail", columnDefinition = "MEDIUMTEXT")
    private String thumbnail;

    /** 手动存档为操作者；AUTO 为 null；PRE_RESTORE 为执行回滚的人。 */
    @Column(name = "author_id", length = 32)
    private String authorId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public BoardVersionEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getBoardId() {
        return boardId;
    }

    public void setBoardId(String boardId) {
        this.boardId = boardId;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getDocJson() {
        return docJson;
    }

    public void setDocJson(String docJson) {
        this.docJson = docJson;
    }

    public long getDocSeq() {
        return docSeq;
    }

    public void setDocSeq(long docSeq) {
        this.docSeq = docSeq;
    }

    public String getThumbnail() {
        return thumbnail;
    }

    public void setThumbnail(String thumbnail) {
        this.thumbnail = thumbnail;
    }

    public String getAuthorId() {
        return authorId;
    }

    public void setAuthorId(String authorId) {
        this.authorId = authorId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (kind == null) {
            kind = BoardVersionKind.AUTO;
        }
    }
}
