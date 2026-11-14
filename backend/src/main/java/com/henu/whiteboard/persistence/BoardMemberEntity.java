package com.henu.whiteboard.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 白板成员实体，对应 wb_board_member；主键为 (board_id, user_id)。
 */
@Entity
@Table(name = "wb_board_member")
@IdClass(BoardMemberEntity.MemberId.class)
public class BoardMemberEntity {

    @Id
    @Column(name = "board_id", length = 32, nullable = false)
    private String boardId;

    @Id
    @Column(name = "user_id", length = 32, nullable = false)
    private String userId;

    /** OWNER / EDITOR / VIEWER，落库统一大写。 */
    @Column(name = "role", length = 16, nullable = false)
    private String role;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    public BoardMemberEntity() {
    }

    public BoardMemberEntity(String boardId, String userId, String role, Instant joinedAt) {
        this.boardId = boardId;
        this.userId = userId;
        this.role = role;
        this.joinedAt = joinedAt;
    }

    public String getBoardId() {
        return boardId;
    }

    public void setBoardId(String boardId) {
        this.boardId = boardId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }

    /** JPA 复合主键；字段名必须与实体主键字段一致。 */
    public static class MemberId implements Serializable {

        private String boardId;
        private String userId;

        public MemberId() {
        }

        public MemberId(String boardId, String userId) {
            this.boardId = boardId;
            this.userId = userId;
        }

        public String getBoardId() {
            return boardId;
        }

        public void setBoardId(String boardId) {
            this.boardId = boardId;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MemberId memberId)) {
                return false;
            }
            return Objects.equals(boardId, memberId.boardId)
                    && Objects.equals(userId, memberId.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(boardId, userId);
        }
    }
}
