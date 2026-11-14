package com.henu.whiteboard.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 白板实体，对应 wb_board 表；doc_json/doc_seq 存最新快照，其余列服务作品库与生命周期。
 */
@Entity
@Table(name = "wb_board")
public class BoardEntity {

    @Id
    @Column(name = "id", length = 32, nullable = false)
    private String id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "owner_id", length = 32, nullable = false)
    private String ownerId;

    @Column(name = "doc_json", columnDefinition = "MEDIUMTEXT")
    private String docJson;

    @Column(name = "doc_seq", nullable = false)
    private long docSeq;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic = true;

    /** DRAFT / ACTIVE / ARCHIVED / TRASHED，落库统一大写。 */
    @Column(name = "status", length = 16, nullable = false)
    private String status = "DRAFT";

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "trashed_at")
    private Instant trashedAt;

    /** 缩略图 data URL（webp/png，解码后 ≤ 256KB）。 */
    @Column(name = "thumbnail", columnDefinition = "MEDIUMTEXT")
    private String thumbnail;

    /** 最近一次快照落库时间，WS init 的 savedAt 取自这里。 */
    @Column(name = "last_saved_at")
    private Instant lastSavedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public BoardEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
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

    public boolean isPublic() {
        return isPublic;
    }

    public void setPublic(boolean aPublic) {
        isPublic = aPublic;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
    }

    public Instant getTrashedAt() {
        return trashedAt;
    }

    public void setTrashedAt(Instant trashedAt) {
        this.trashedAt = trashedAt;
    }

    public String getThumbnail() {
        return thumbnail;
    }

    public void setThumbnail(String thumbnail) {
        this.thumbnail = thumbnail;
    }

    public Instant getLastSavedAt() {
        return lastSavedAt;
    }

    public void setLastSavedAt(Instant lastSavedAt) {
        this.lastSavedAt = lastSavedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = "DRAFT";
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
