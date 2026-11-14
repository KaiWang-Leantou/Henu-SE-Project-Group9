package com.henu.whiteboard.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 用户实体，对应 wb_user 表；游客只有 id/nickname，升级后补 username/password_hash。
 */
@Entity
@Table(name = "wb_user")
public class UserEntity {

    @Id
    @Column(name = "id", length = 32, nullable = false)
    private String id;

    /** 游客为 null，升级后写入。 */
    @Column(name = "username", length = 32)
    private String username;

    /** BCrypt 哈希；游客为 null。 */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "nickname", length = 50, nullable = false)
    private String nickname;

    @Column(name = "avatar", length = 255)
    private String avatar;

    @Column(name = "is_guest", nullable = false)
    private boolean guest = true;

    /** ACTIVE / DISABLED。 */
    @Column(name = "status", length = 16, nullable = false)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    public UserEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public boolean isGuest() {
        return guest;
    }

    public void setGuest(boolean guest) {
        this.guest = guest;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = "ACTIVE";
        }
    }
}
