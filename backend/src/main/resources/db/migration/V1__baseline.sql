-- V1 基线：白板快照表与操作日志表（与现有 JPA 实体一一对应）。
-- 兼容性约定：不写 ENGINE / CHARSET；时间统一 TIMESTAMP(3)，大文本用 TEXT 系列，
-- 布尔用 BOOLEAN。doc_json/cmd_json 用 MEDIUMTEXT：MySQL 的 TEXT 仅 64KB，装不下整板文档。

CREATE TABLE wb_board (
    id         VARCHAR(32)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    owner_id   VARCHAR(32)  NOT NULL,
    doc_json   MEDIUMTEXT   NULL,
    doc_seq    BIGINT       NOT NULL DEFAULT 0,
    is_public  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT pk_wb_board PRIMARY KEY (id)
);

CREATE INDEX idx_wb_board_owner ON wb_board (owner_id);
CREATE INDEX idx_wb_board_updated ON wb_board (updated_at);

CREATE TABLE wb_board_op (
    board_id   VARCHAR(32)  NOT NULL,
    seq        BIGINT       NOT NULL,
    actor_id   VARCHAR(32)  NOT NULL,
    ch         VARCHAR(16)  NOT NULL,
    cmd_json   MEDIUMTEXT   NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT pk_wb_board_op PRIMARY KEY (board_id, seq)
);

CREATE INDEX idx_wb_board_op_created ON wb_board_op (created_at);
