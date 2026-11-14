-- V2：用户体系 + 白板归属/成员 + 作品库元数据。
-- 兼容性约定：不写 ENGINE / CHARSET；时间统一 TIMESTAMP(3)，大文本用 TEXT 系列，
-- 布尔用 BOOLEAN。thumbnail 用 MEDIUMTEXT 以容纳 ≤256KB 图片的 base64（MySQL TEXT 仅 64KB）。

ALTER TABLE wb_board ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE wb_board ADD COLUMN archived_at TIMESTAMP(3) NULL;
ALTER TABLE wb_board ADD COLUMN trashed_at TIMESTAMP(3) NULL;
ALTER TABLE wb_board ADD COLUMN thumbnail MEDIUMTEXT NULL;
ALTER TABLE wb_board ADD COLUMN last_saved_at TIMESTAMP(3) NULL;

CREATE TABLE wb_user (
    id            VARCHAR(32)  NOT NULL,
    username      VARCHAR(32)  NULL,
    password_hash VARCHAR(100) NULL,
    nickname      VARCHAR(50)  NOT NULL,
    avatar        VARCHAR(255) NULL,
    is_guest      BOOLEAN      NOT NULL DEFAULT TRUE,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMP(3) NOT NULL,
    last_login_at TIMESTAMP(3) NULL,
    CONSTRAINT pk_wb_user PRIMARY KEY (id),
    CONSTRAINT uk_wb_user_username UNIQUE (username)
);

CREATE TABLE wb_board_member (
    board_id  VARCHAR(32)  NOT NULL,
    user_id   VARCHAR(32)  NOT NULL,
    role      VARCHAR(16)  NOT NULL,
    joined_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT pk_wb_board_member PRIMARY KEY (board_id, user_id)
);

CREATE INDEX idx_wb_board_member_user ON wb_board_member (user_id);
