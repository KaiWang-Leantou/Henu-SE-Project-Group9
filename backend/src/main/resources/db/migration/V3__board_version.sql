-- V3：版本历史表（docs/07 §1.1）。
-- 兼容性约定：不写 ENGINE / CHARSET；时间统一 TIMESTAMP(3)，大文本用 TEXT 系列。
-- doc_json / thumbnail 用 MEDIUMTEXT：MySQL 的 TEXT 仅 64KB，装不下整板文档与缩略图 base64。

CREATE TABLE wb_board_version (
    id         VARCHAR(32)  NOT NULL,
    board_id   VARCHAR(32)  NOT NULL,
    version_no INT          NOT NULL,
    kind       VARCHAR(16)  NOT NULL,
    label      VARCHAR(100) NULL,
    doc_json   MEDIUMTEXT   NOT NULL,
    doc_seq    BIGINT       NOT NULL,
    thumbnail  MEDIUMTEXT   NULL,
    author_id  VARCHAR(32)  NULL,
    created_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT pk_wb_board_version PRIMARY KEY (id),
    CONSTRAINT uk_board_version UNIQUE (board_id, version_no)
);

CREATE INDEX idx_board_version_time ON wb_board_version (board_id, created_at);
