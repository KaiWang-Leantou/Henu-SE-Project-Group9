package com.henu.whiteboard.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 操作日志实体，对应 wb_board_op 表；主键为 (board_id, seq)。
 */
@Entity
@Table(name = "wb_board_op")
@IdClass(BoardOpEntity.OpId.class)
public class BoardOpEntity {

    @Id
    @Column(name = "board_id", length = 32, nullable = false)
    private String boardId;

    @Id
    @Column(name = "seq", nullable = false)
    private Long seq;

    @Column(name = "actor_id", length = 32, nullable = false)
    private String actorId;

    @Column(name = "ch", length = 16, nullable = false)
    private String ch;

    @Column(name = "cmd_json", columnDefinition = "MEDIUMTEXT", nullable = false)
    private String cmdJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public BoardOpEntity() {
    }

    public String getBoardId() {
        return boardId;
    }

    public void setBoardId(String boardId) {
        this.boardId = boardId;
    }

    public Long getSeq() {
        return seq;
    }

    public void setSeq(Long seq) {
        this.seq = seq;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public String getCh() {
        return ch;
    }

    public void setCh(String ch) {
        this.ch = ch;
    }

    public String getCmdJson() {
        return cmdJson;
    }

    public void setCmdJson(String cmdJson) {
        this.cmdJson = cmdJson;
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
    }

    /** JPA 复合主键；字段名必须与实体主键字段一致。 */
    public static class OpId implements Serializable {

        private String boardId;
        private Long seq;

        public OpId() {
        }

        public OpId(String boardId, Long seq) {
            this.boardId = boardId;
            this.seq = seq;
        }

        public String getBoardId() {
            return boardId;
        }

        public void setBoardId(String boardId) {
            this.boardId = boardId;
        }

        public Long getSeq() {
            return seq;
        }

        public void setSeq(Long seq) {
            this.seq = seq;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof OpId opId)) {
                return false;
            }
            return Objects.equals(boardId, opId.boardId) && Objects.equals(seq, opId.seq);
        }

        @Override
        public int hashCode() {
            return Objects.hash(boardId, seq);
        }
    }
}
